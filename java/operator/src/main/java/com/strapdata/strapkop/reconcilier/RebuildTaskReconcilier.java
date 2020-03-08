package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.task.RebuildTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class RebuildTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RebuildTaskReconcilier.class);
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public RebuildTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                  final DataCenterUpdateReconcilier dataCenterUpdateReconcilier,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final JmxmpElassandraProxy jmxmpElassandraProxy,
                                  final CustomObjectsApi customObjectsApi,
                                  final ApplicationContext context,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final MeterRegistry meterRegistry,
                                  final ElassandraNodeStatusCache elassandraNodeStatusCache) {
        super(reconcilierObserver, "rebuild", k8sResourceUtils, meterRegistry, dataCenterUpdateReconcilier, elassandraNodeStatusCache);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    public BlockReason blockReason() {
        return BlockReason.REBUILD;
    }

    /**
     * Execute backup concurrently on all nodes
     *
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws Exception {
        final RebuildTaskSpec rebuildTaskSpec = task.getSpec().getRebuild();

        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        logger.info("datacenter={} rebuild={} executed on destination DC", dc.id(), task.id());
        if (!dc.getStatus().getPhase().equals(DataCenterPhase.RUNNING)) {
            // wait a running datacenter to rebuild.
            return Single.just(task.getStatus().getPhase());
        }

        // rebuild in parallel to stream data
        List<CompletableSource> todoList = new ArrayList<>();
        for (String pod : pods) {
            todoList.add(jmxmpElassandraProxy.rebuild(ElassandraPod.fromName(dc, pod), task.getSpec().getRebuild().getSrcDcName(), null)
                    .toSingleDefault(task)
                    .map(t -> {
                        // update pod status in memory (no etcd update)
                        task.getStatus().getPods().put(pod, TaskPhase.SUCCEED);
                        return t;
                    })
                    .ignoreElement()
                    .onErrorResumeNext(throwable -> {
                        logger.error("datacenter={} rebuild={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                        return updateTaskPodStatus(dc, task, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                    }));
        }
        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                .andThen(finalizeTaskStatus(dc, task))
                .flatMap(taskPhase -> {
                    // submit a dc status update in the working queue to update dc bootstrapped=true
                    logger.info("datacenter={} rebuild={} done on destination DC, update status bootstrapped=true", dc.id(), task.id());
                    dc.getStatus().setBootstrapped(true);
                    return k8sResourceUtils.updateDataCenterStatus(dc).map(o -> TaskPhase.SUCCEED);
                });
    }

    @Override
    public Completable initializePodMap(Task task, DataCenter dc) {
        return initializePodMapWithUnknownStatus(task, dc);
    }
}
