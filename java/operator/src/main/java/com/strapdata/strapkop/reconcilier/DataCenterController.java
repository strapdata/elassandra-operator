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

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.cache.*;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.K8sSupplier;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.k8s.Pod;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.model.k8s.datacenter.ReaperPhase;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.plugins.ReaperPlugin;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Date;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * DC controller
 */
@Singleton
public class DataCenterController {

    private final Logger logger = LoggerFactory.getLogger(DataCenterController.class);

    @Inject
    ApplicationContext context;

    @Inject
    PluginRegistry pluginRegistry;

    @Inject
    CqlRoleManager cqlRoleManager;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    DataCenterStatusCache dataCenterStatusCache;

    @Inject
    StatefulsetCache statefulsetCache;

    @Inject
    ServiceAccountCache serviceAccountCache;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    ReconcilierObserver reconcilierObserver;

    @Inject
    ReaperPlugin reaperPlugin;

    public Completable reconcile(DataCenter dataCenter, Completable action) throws Exception {
        return reconcilierObserver.onReconciliationBegin().toSingleDefault(dataCenter)
                .flatMapCompletable(dc -> {
                    try {
                        // call the statefullset reconciliation  (before scaling up/down to properly stream data according to the adjusted RF)
                        logger.trace("datacenter={} processing a DC reconciliation", dc.id());
                        return action;
                    } catch (Exception e) {
                        logger.error("datacenter={} an error occurred while processing DataCenter update reconciliation", dc.id(), e);
                        if (dc != null) {
                            Key key = new Key(dataCenter.getMetadata());
                            DataCenterStatus dataCenterStatus = dataCenterStatusCache.getOrDefault(key, dataCenter.getStatus());
                            dataCenterStatus.setLastError(e.toString());
                            dataCenterStatus.setLastErrorTime(new Date());
                            return k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus()).flatMapCompletable(o -> { throw e; });
                        }
                        throw e;
                    }
                })
                .doOnError(t -> { if (!(t instanceof ReconcilierShutdownException)) reconcilierObserver.failedReconciliationAction(); })
                .doOnComplete(reconcilierObserver.endReconciliationAction())
                .observeOn(Schedulers.io());
    }

    public Completable initDatacenter(DataCenter dc, Operation op) throws Exception {
        return reconcile(dc,
                statefulsetCache.loadIfAbsent(dc)
                .flatMap(x -> (dc.getSpec().getNetworking().nodeInfoRequired())
                        ? serviceAccountCache.load(OperatorNames.nodeInfoServiceAccount(dc), dc.getMetadata().getNamespace()).map(sa -> x)
                        : Single.just(x))
                .flatMap(t -> fetchDataCentersSameClusterAndNamespace(dc))
                .flatMapCompletable(dcIterable -> context.createBean(DataCenterUpdateAction.class, dc, op)
                        .setSibilingDc(StreamSupport.stream(dcIterable.spliterator(), false)
                                .filter(d -> !d.getSpec().getDatacenterName().equals(dc.getSpec().getDatacenterName()))
                                .map(d -> d.getSpec().getDatacenterName())
                                .collect(Collectors.toList()))
                        .initDatacenter()
                )
        );
    }

    /**
     * Called when the DC CRD is updated, involving a rolling update of sts.
     */
    public Completable updateDatacenter(DataCenter dc, Operation op) throws Exception {
        return reconcile(dc,
                statefulsetCache.loadIfAbsent(dc)
                .flatMap(t -> fetchDataCentersSameClusterAndNamespace(dc))
                .flatMapCompletable(dcIterable -> context.createBean(DataCenterUpdateAction.class, dc, op)
                        .setSibilingDc(StreamSupport.stream(dcIterable.spliterator(), false)
                                .filter(d -> !d.getSpec().getDatacenterName().equals(dc.getSpec().getDatacenterName()))
                                .map(d -> d.getSpec().getDatacenterName())
                                .collect(Collectors.toList()))
                        .updateDatacenterSpec()
                )
        );
    }

    public Completable statefulsetStatusUpdate(DataCenter dc, Operation op, V1StatefulSet sts) throws Exception {
        return reconcile(dc, statefulsetCache.loadIfAbsent(dc)
                .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dc, op))
                .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.statefulsetStatusUpdated(sts)));
    }

    public Completable deploymentAvailable(DataCenter dc, Operation op, V1Deployment deployment) throws Exception {
        String app = deployment.getMetadata().getLabels().get(OperatorLabels.APP);
        if ("reaper".equals(app)) {
            dc.getStatus().setReaperPhase(ReaperPhase.RUNNING);
            return reconcile(dc,
                    reaperPlugin.reconcile(context.createBean(DataCenterUpdateAction.class, dc, op))
                            .flatMapCompletable(b -> k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus()).ignoreElement()));
        }
        return Completable.complete();
    }

    public Completable unschedulablePod(Pod pod) throws Exception {
        final Key key = new Key(pod.getParent(), pod.getNamespace());
        DataCenter dc = dataCenterCache.get(key);
        if (dc != null) {
            Operation op = new Operation()
                    .withLastTransitionTime(new Date())
                    .withTriggeredBy("unschedulable pod=" + pod.getName());
            return reconcile(dc, statefulsetCache.loadIfAbsent(dc)
                    .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dc, op))
                    .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.unschedulablePod(pod))
                    .andThen(k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus()).ignoreElement())
            );
        }
        return Completable.complete();
    }

    /**
     * Called when desired state changes
     */
    public Completable deleteDatacenter(final DataCenter dataCenter) throws Exception {
        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
        meterRegistry.counter("datacenter.delete").increment();

        return reconcilierObserver.onReconciliationBegin()
                .andThen(pluginRegistry.deleteAll(dataCenter))
                .andThen(context.createBean(DataCenterDeleteAction.class, dataCenter).deleteDataCenter(cqlSessionHandler))
                .doOnError(t -> { // TODO au lieu de faire le deleteDC en premier ne faut-il pas faire une action deleteDC sur erreur ou simplement logguer les erreur de deletePlugin ???
                    logger.warn("An error occured during delete datacenter action : {} ", t.getMessage(), t);
                    if (!(t instanceof ReconcilierShutdownException)) {
                        reconcilierObserver.failedReconciliationAction();
                    }
                })
                .doOnComplete(reconcilierObserver.endReconciliationAction())
                .doFinally(() -> cqlSessionHandler.close());
    }

    public Completable taskDone(final DataCenter dc, final Task task) throws Exception {
        return reconcile(dc,
                statefulsetCache.loadIfAbsent(dc)
                        .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dc,
                                new Operation()
                                        .withLastTransitionTime(new Date())
                                        .withTriggeredBy("dc-after-task-" + task.getMetadata().getName())))
                .flatMapCompletable(dataCenterUpdateAction ->  dataCenterUpdateAction.taskDone(task))
                .andThen(k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus()).ignoreElement()));
    }

    /**
     * Fetch datacenters of the same cluster in the same namespace to automatically add seeds
     * @param dc
     * @return
     */
    public Single<Iterable<DataCenter>> fetchDataCentersSameClusterAndNamespace(DataCenter dc) throws ApiException {
        return K8sResourceUtils.listNamespacedResources(dc.getMetadata().getNamespace(), new K8sSupplier<Iterable<DataCenter>>() {
            @Override
            public Iterable<DataCenter> get() throws ApiException {
                final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(
                        OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR,
                        OperatorLabels.CLUSTER, dc.getSpec().getClusterName(),
                        OperatorLabels.APP, OperatorLabels.ELASSANDRA_APP));
                return k8sResourceUtils.listNamespacedDataCenters(dc.getMetadata().getNamespace(), labelSelector);
            }
        });
    }
}
