package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.task.RebuildTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class RebuildTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RebuildTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final WorkQueue workQueue;

    public RebuildTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final SidecarClientFactory sidecarClientFactory,
                                  final CustomObjectsApi customObjectsApi,
                                  final ApplicationContext context,
                                  final WorkQueue workQueue,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "rebuild", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.workQueue = workQueue;
    }

    public BlockReason blockReason() {
        return BlockReason.REBUILD;
    }

    /**
     * Execute backup concurrently on all nodes
     *
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
            for (CqlKeyspace systemKs : CqlKeyspaceManager.SYSTEM_KEYSPACES)
                replicationMap.putIfAbsent(systemKs.getName(), systemKs.getRf());

            final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
            Completable todo = Completable.complete();
            for (Map.Entry<String, Integer> entry : replicationMap.entrySet()) {
                todo = todo.andThen(this.cqlKeyspaceManager.updateKeyspaceReplicationMap(dc, rebuildTaskSpec.getDstDcName(), entry.getKey(), Math.min(entry.getValue(), rebuildTaskSpec.getDstDcSize()), cqlSessionHandler, false));
            }

            // flush sstables in parallel to stream properly
            List<CompletableSource> todoList = new ArrayList<>();
            for(String pod : pods) {
                todoList.add(sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod)).flush(null)
                        .andThen(updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.SUCCEED))
                        .onErrorResumeNext(throwable -> {
                            logger.error("Error while executing rebuild on pod={}", pod, throwable);
                            return updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                        })
                );
            }
            todo.andThen(Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])))
                    .andThen(finalizeTaskStatus(dc, taskWrapper));
        }

        if (rebuildTaskSpec.getDstDcName().equals(dc.getSpec().getDatacenterName())) {
            if (!dc.getStatus().getPhase().equals(DataCenterPhase.RUNNING)) {
                // wait a running datacenter to rebuild.
                return Single.just(task.getStatus().getPhase());
            }

            // rebuild in parallel to stream data
            List<CompletableSource> todoList = new ArrayList<>();
            for(String pod : pods) {
                todoList.add(sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod)).rebuild(task.getSpec().getRebuild().getSrcDcName(), null)
                        .onErrorResumeNext(throwable -> {
                            logger.error("Error while executing rebuild on pod={}", pod, throwable);
                            return updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                        })
                        .andThen(updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.SUCCEED))

                );
            }
            return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                    .andThen(finalizeTaskStatus(dc, taskWrapper))
                    .flatMap(taskPhase -> {
                        // submit a dc status update in the working queue to update dc bootstrapped=true
                        if (TaskPhase.SUCCEED.equals(taskPhase)) {
                            workQueue.submit(new ClusterKey(dc), k8sResourceUtils.readDatacenter(new Key(dc.getMetadata()))
                                    .flatMapCompletable(dataCenter -> {
                                        logger.info("Rebuild done datacenter={} bootstrapped=true", dc.id());
                                        dataCenter.getStatus().setBootstrapped(true);
                                        return k8sResourceUtils.updateDataCenter(dc).ignoreElement();
                                    }));
                        } else {
                            logger.warn("Rebuild task={} failed, dc={} boostrapped=false", task.getMetadata().getName(), dc.getMetadata().getName());
                        }
                        return Single.just(taskPhase);
                    });
        }

        throw new IllegalArgumentException("Datacenter name does not match src or dst DC name");
    }
}
