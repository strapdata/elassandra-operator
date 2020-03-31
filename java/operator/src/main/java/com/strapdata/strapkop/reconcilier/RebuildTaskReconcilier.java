package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.Lists;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.RebuildTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
                                  final OperatorConfig operatorConfig,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final JmxmpElassandraProxy jmxmpElassandraProxy,
                                  final ApplicationContext context,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final MeterRegistry meterRegistry,
                                  final DataCenterController dataCenterController,
                                  final DataCenterCache dataCenterCache,
                                  ExecutorFactory executorFactory,
                                  @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, "rebuild", operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
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
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws Exception {
        final RebuildTaskSpec rebuildTaskSpec = task.getSpec().getRebuild();
        task.getStatus().setStartDate(new Date());

        logger.info("datacenter={} task={} task.status={} rebuild from srcDc={} executed on pods={}",
                dc.id(), task.id(), task.getStatus(), rebuildTaskSpec.getSrcDcName(),
                Lists.newArrayList(pods).stream().map(p->p.getMetadata().getName()).collect(Collectors.toList()));

        // rebuild in parallel to stream data
        List<CompletableSource> todoList = new ArrayList<>();
        for (V1Pod v1Pod : pods) {
            ElassandraPod pod = ElassandraPod.fromV1Pod(v1Pod);
            todoList.add(jmxmpElassandraProxy.rebuild(pod, rebuildTaskSpec.getSrcDcName(), null)
                    .toSingleDefault(task)
                    .map(t -> {
                        // update pod status in memory (no etcd update)
                        task.getStatus().getPods().put(pod.getName(), TaskPhase.SUCCEED);
                        logger.debug("datacenter={} rebuild={} srcDc={} done", dc.id(), task.id(), rebuildTaskSpec.getSrcDcName());
                        return t;
                    })
                    .ignoreElement()
                    .onErrorResumeNext(throwable -> {
                        logger.error("datacenter={} rebuild={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                        task.getStatus().setLastMessage(throwable.getMessage());
                        task.getStatus().getPods().put(pod.getName(), TaskPhase.FAILED);
                        return Completable.complete();
                    }));
        }
        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]))
                .andThen(finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED));
    }

    @Override
    public Single<Iterable<V1Pod>> listPods(Task task, DataCenter dc) {
        return initializePodMapWithWaitingStatus(task, dc);
    }

    /**
     * Trigger reconciliation for plugin after rebuild done
     * @return
     */
    @Override
    public boolean reconcileDataCenterWhenDone() {
        return true;
    }
}
