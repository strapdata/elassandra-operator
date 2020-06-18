/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.Lists;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.k8s.task.UpdateRoutingTaskSpec;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import com.strapdata.strapkop.sidecar.HttpClientFactory;
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
    private final HttpClientFactory httpClientFactory;

    public UpdateRoutingTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                        final OperatorConfig operatorConfig,
                                        final K8sResourceUtils k8sResourceUtils,
                                        final JmxmpElassandraProxy jmxmpElassandraProxy,
                                        final HttpClientFactory httpClientFactory,
                                        final ApplicationContext context,
                                        final CqlRoleManager cqlRoleManager,
                                        final CqlKeyspaceManager cqlKeyspaceManager,
                                        final MeterRegistry meterRegistry,
                                        final DataCenterController dataCenterController,
                                        final DataCenterCache dataCenterCache,
                                        final DataCenterStatusCache dataCenterStatusCache,
                                        ExecutorFactory executorFactory,
                                        @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, dataCenterStatusCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.httpClientFactory = httpClientFactory;
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
        final UpdateRoutingTaskSpec updateRoutingTaskSpec = task.getSpec().getUpdateRouting();
        task.getStatus().setStartDate(new Date());

        logger.info("datacenter={} task={} task.status={} executed on pods={} updateRoutingIndices={}",
                dc.id(), task.id(), task.getStatus(),
                Lists.newArrayList(pods).stream().map(p -> p.getMetadata().getName()).collect(Collectors.toList()),
                updateRoutingTaskSpec.getIndices());

        if (dc.getSpec().getElasticsearch().getEnterprise().getEnabled() == false) {
            task.getStatus().setLastMessage("No Elasticsearch Enterprise enabled");
            finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.IGNORED, "updateRouting");
        }

        cqlRoleManager.addIfAbsent(dc, CqlRole.STRAPKOP_ROLE.getUsername(), () -> CqlRole.STRAPKOP_ROLE.duplicate());
        CqlRole strapkopRole = cqlRoleManager.get(dc, CqlRole.STRAPKOP_ROLE.getUsername());
        logger.debug("roles={} strapkopRole={}", strapkopRole, cqlRoleManager.get(dc), strapkopRole);

        // rebuild in parallel to stream data
        Completable reloadLicense = null;
        List<CompletableSource> routingUpdates = new ArrayList<>();
        for (V1Pod v1Pod : pods) {
            final ElassandraPod pod = ElassandraPod.fromV1Pod(v1Pod)
                    .setEsPort(dc.getSpec().getElasticsearch().getHttpPort())
                    .setSsl(dc.getSpec().getCassandra().getSsl());
            if (reloadLicense == null) {
                reloadLicense = httpClientFactory.clientForPod(pod, strapkopRole).reloadLicense()
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} updateRouting={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            task.getStatus().getPods().put(pod.getName(), TaskPhase.FAILED);
                            return Completable.complete();
                        });
            }
            routingUpdates.add(httpClientFactory.clientForPod(pod, strapkopRole).updateRouting(updateRoutingTaskSpec.getIndices())
                    .toSingleDefault(task)
                    .map(t -> {
                        // update pod status in memory (no etcd update)
                        task.getStatus().getPods().put(pod.getName(), TaskPhase.SUCCEED);
                        logger.debug("datacenter={} task={} updateRouting done", dc.id(), task.id());
                        return t;
                    })
                    .ignoreElement()
                    .onErrorResumeNext(throwable -> {
                        logger.error("datacenter={} elasticReset={} Error while executing destination DC on pod={}", dc.id(), task.id(), pod, throwable);
                        task.getStatus().setLastMessage(throwable.getMessage());
                        task.getStatus().getPods().put(pod.getName(), TaskPhase.FAILED);
                        return Completable.complete();
                    }));
        }

        return (reloadLicense == null ? Completable.complete() : reloadLicense)
                .andThen(Completable.mergeArray(routingUpdates.toArray(new CompletableSource[routingUpdates.size()])))
                .andThen(finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "updateRouting"));
    }

    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }

    /**
     * Trigger reconciliation for plugin after rebuild done
     *
     * @return
     */
    @Override
    public boolean reconcileDataCenterWhenDone() {
        return false;
    }
}
