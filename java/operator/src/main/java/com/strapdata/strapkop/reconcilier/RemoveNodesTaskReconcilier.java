package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.RemoveNodesTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Iterator;
import java.util.List;

/**
 * Remove all nodes of a remote datacenter.
 */
@Singleton
@Infrastructure
public class RemoveNodesTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RemoveNodesTaskReconcilier.class);
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public RemoveNodesTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                      final OperatorConfig operatorConfig,
                                      final K8sResourceUtils k8sResourceUtils,
                                      final JmxmpElassandraProxy jmxmpElassandraProxy,
                                      final CustomObjectsApi customObjectsApi,
                                      final ApplicationContext context,
                                      final CqlRoleManager cqlRoleManager,
                                      final CqlKeyspaceManager cqlKeyspaceManager,
                                      final DataCenterController dataCenterController,
                                      final DataCenterCache dataCenterCache,
                                      final MeterRegistry meterRegistry,
                                      ExecutorFactory executorFactory,
                                      @Named("tasks") UserExecutorConfiguration userExecutorConfiguration ) {
        super(reconcilierObserver, operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    /**
     * Remove node of a stopped datacenters.
     *
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws Exception {
        final RemoveNodesTaskSpec removeNodesTaskSpec = task.getSpec().getRemoveNodes();
        final String dcName = removeNodesTaskSpec.getDcName();

        if (Strings.isNullOrEmpty(dcName)) {
            logger.warn("datacenter={} removeNodes={} dcName not set, ignoring task={}", dc.id(), task.id());
            return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "removeNodes");
        }

        Iterator<V1Pod> it = pods.iterator();
        if (!it.hasNext())
            return Completable.complete();

        ElassandraPod pod = ElassandraPod.fromV1Pod(it.next());
        return jmxmpElassandraProxy.removeDcNodes(pod, dcName)
                .toSingleDefault(pod)
                .flatMapCompletable(p -> {
                    task.getStatus().getPods().put(p.getName(), TaskPhase.SUCCEED);
                    logger.debug("datacenter={} task={} removeNodes dcName={} done", dc.id(), task.id(), dcName);
                    return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "removeNodes");
                })
                .onErrorResumeNext(throwable -> {
                    logger.error("datacenter={} task={} Error removing nodes of dc={} error:{}",
                            dc.id(), task.id(), dcName, throwable.getMessage());
                    task.getStatus().setLastMessage(throwable.getMessage());
                    return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.FAILED, "removeNodes");
                });
    }

    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return getElassandraRunningPods(dc).map(pods ->
                initTaskStatusPodMap(task, pods.size() == 0 ? ImmutableList.of() : ImmutableList.of(pods.get(0))));
    }
}
