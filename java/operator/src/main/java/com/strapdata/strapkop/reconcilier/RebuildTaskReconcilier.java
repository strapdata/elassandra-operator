package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.task.RebuildTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class RebuildTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RebuildTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public RebuildTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final SidecarClientFactory sidecarClientFactory,
                                  final CustomObjectsApi customObjectsApi,
                                  final ApplicationContext context,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "rebuild", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    public BlockReason blockReason() {
        return BlockReason.REBUILD;
    }

    /**
     * Execute backup concurrently on all nodes
     * @param taskWrapper
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception {
        final Task task = taskWrapper.getTask();
        final RebuildTaskSpec rebuildTaskSpec = task.getSpec().getRebuild();

        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (rebuildTaskSpec.getSrcDcName().equals(dc.getSpec().getDatacenterName())) {
            // manage CQL replication map update before streaming
            final Map<String, Integer> replicationMap = new HashMap<>();
            replicationMap.putAll(rebuildTaskSpec.getReplicationMap());
            for(CqlKeyspace systemKs : CqlKeyspaceManager.SYSTEM_KEYSPACES)
                replicationMap.putIfAbsent(systemKs.getName(), systemKs.getRf());

            final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
            Completable todo = Completable.complete();
            for(Map.Entry<String, Integer> entry : replicationMap.entrySet()) {
                todo = todo.andThen(this.cqlKeyspaceManager.updateKeyspaceReplicationMap(dc, rebuildTaskSpec.getDstDcName(), entry.getKey(), Math.min(entry.getValue(), rebuildTaskSpec.getDstDcSize()), cqlSessionHandler, false));
            }

            // flush sstables to stream properly
            return todo.andThen(Observable.fromIterable(pods)
                    .subscribeOn(Schedulers.io())
                    .flatMapSingle(pod -> {
                        // execute cleanup on a each pod sequentially
                        TaskPhase podPhase = TaskPhase.SUCCEED;
                        try {
                            final Throwable t = sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                                    .flush(null).blockingGet();
                            if (t != null) throw t;
                        } catch (Throwable throwable) {
                            logger.error("Error while executing rebuild on {}", pod, throwable);
                            podPhase = TaskPhase.FAILED;
                            task.getStatus().setLastMessage(throwable.getMessage());
                        }
                        task.getStatus().getPods().put(pod, podPhase);
                        return updateTaskStatus(dc, taskWrapper, TaskPhase.RUNNING).toSingleDefault(new Tuple2<String, TaskPhase>(pod, podPhase));
                    })
                    .toList()
                    .map(list -> {
                        // finally compute the task phase
                        TaskPhase taskPhase = TaskPhase.SUCCEED;
                        for (Tuple2<String, TaskPhase> t : list) {
                            if (t._2.equals(TaskPhase.FAILED))
                                taskPhase = TaskPhase.FAILED;
                        }
                        return taskPhase;
                    }));
        }

        if (rebuildTaskSpec.getDstDcName().equals(dc.getSpec().getDatacenterName()) && dc.getStatus().getPhase().equals(DataCenterPhase.RUNNING)) {
            // excute a nodetool rebuild on all nodes
            return Observable.fromIterable(pods)
                    .subscribeOn(Schedulers.io())
                    .flatMapSingle(pod -> {
                        // execute cleanup on a each pod sequentially
                        TaskPhase podPhase = TaskPhase.SUCCEED;
                        try {
                            final Throwable t = sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                                    .rebuild(task.getSpec().getRebuild().getSrcDcName(), null).blockingGet();
                            if (t != null) throw t;
                        } catch (Throwable throwable) {
                            logger.error("Error while executing rebuild on {}", pod, throwable);
                            podPhase = TaskPhase.FAILED;
                            task.getStatus().setLastMessage(throwable.getMessage());
                        }
                        task.getStatus().getPods().put(pod, podPhase);
                        return updateTaskStatus(dc, taskWrapper, TaskPhase.RUNNING).toSingleDefault(new Tuple2<String, TaskPhase>(pod, podPhase));
                    })
                    .toList()
                    .map(list -> {
                        // finally compute the task phase
                        TaskPhase taskPhase = TaskPhase.SUCCEED;
                        for (Tuple2<String, TaskPhase> t : list) {
                            if (t._2.equals(TaskPhase.FAILED))
                                taskPhase = TaskPhase.FAILED;
                        }
                        return taskPhase;
                    });
        }

        throw new IllegalArgumentException("Datacenter name does not match src or dst DC name");
    }
}
