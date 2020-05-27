package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.UpdateRoutingTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
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

/**
 * Reload Elassandra Enterprise License and update routing table after DC rebuild.
 */
@Singleton
@Infrastructure
public class UpdateRoutingTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(UpdateRoutingTaskReconcilier.class);
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final SidecarClientFactory sidecarClientFactory;

    public UpdateRoutingTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                        final OperatorConfig operatorConfig,
                                        final K8sResourceUtils k8sResourceUtils,
                                        final JmxmpElassandraProxy jmxmpElassandraProxy,
                                        final SidecarClientFactory sidecarClientFactory,
                                        final ApplicationContext context,
                                        final CqlRoleManager cqlRoleManager,
                                        final CqlKeyspaceManager cqlKeyspaceManager,
                                        final MeterRegistry meterRegistry,
                                        final DataCenterController dataCenterController,
                                        final DataCenterCache dataCenterCache,
                                        ExecutorFactory executorFactory,
                                        @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, "elasticReset", operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.sidecarClientFactory = sidecarClientFactory;
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
        final UpdateRoutingTaskSpec elasticResetTaskSpec = task.getSpec().getUpdateRouting();
        task.getStatus().setStartDate(new Date());

        logger.info("datacenter={} task={} task.status={} elasticReset executed on pods={} updateRoutingIndices={}",
                dc.id(), task.id(), task.getStatus(),
                Lists.newArrayList(pods).stream().map(p->p.getMetadata().getName()).collect(Collectors.toList()),
                elasticResetTaskSpec.getIndices());

        if (dc.getSpec().getElasticsearch().getEnterprise().getEnabled() == false) {
            task.getStatus().setLastMessage("No Elasticsearch Enterprise enabled");
            finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.IGNORED);
        }

        cqlRoleManager.addIfAbsent(dc, CqlRole.STRAPKOP_ROLE.getUsername(), () -> CqlRole.STRAPKOP_ROLE.duplicate());
        CqlRole strapkopRole = cqlRoleManager.get(dc, CqlRole.STRAPKOP_ROLE.getUsername());
        logger.debug("roles={} strapkopRole={}", strapkopRole, cqlRoleManager.get(dc), strapkopRole);

        // rebuild in parallel to stream data
        Completable reloadLicense = null;
        List<CompletableSource> routingUpdates = new ArrayList<>();
        for (V1Pod v1Pod : pods) {
            ElassandraPod pod = ElassandraPod.fromV1Pod(v1Pod)
                    .setEsPort(dc.getSpec().getElasticsearch().getHttpPort())
                    .setSsl(dc.getSpec().getCassandra().getSsl());
            if (reloadLicense == null) {
                reloadLicense = sidecarClientFactory.clientForPod(pod, strapkopRole).reloadLicense()
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} elasticReset={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            task.getStatus().getPods().put(pod.getName(), TaskPhase.FAILED);
                            return Completable.complete();
                        });
            }
            if (!Strings.isNullOrEmpty(elasticResetTaskSpec.getIndices())) {
                routingUpdates.add(sidecarClientFactory.clientForPod(pod, strapkopRole).updateRouting(elasticResetTaskSpec.getIndices())
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} elasticReset={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            task.getStatus().getPods().put(pod.getName(), TaskPhase.FAILED);
                            return Completable.complete();
                        }));
            }
        }

        return (reloadLicense == null ? Completable.complete() : reloadLicense)
                .andThen(Completable.mergeArray(routingUpdates.toArray(new CompletableSource[routingUpdates.size()])))
                .andThen(finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED));
    }

    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }

    /**
     * Trigger reconciliation for plugin after rebuild done
     * @return
     */
    @Override
    public boolean reconcileDataCenterWhenDone() {
        return false;
    }
}
