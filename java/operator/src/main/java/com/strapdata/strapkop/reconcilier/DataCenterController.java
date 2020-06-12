package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.cache.*;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.K8sSupplier;
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

    private final ApplicationContext context;
    private final PluginRegistry pluginRegistry;
    private final CqlRoleManager cqlRoleManager;
    private final DataCenterCache dataCenterCache;
    private final DataCenterStatusCache dataCenterStatusCache;
    private final StatefulsetCache statefulsetCache;
    private final SidecarConnectionCache sidecarConnectionCache;
    private final MeterRegistry meterRegistry;
    private final K8sResourceUtils k8sResourceUtils;
    private final ReconcilierObserver reconcilierObserver;
    private final ReaperPlugin reaperPlugin;

    public DataCenterController(final ReconcilierObserver reconcilierObserver,
                                final ApplicationContext context,
                                final CqlRoleManager cqlRoleManager,
                                final PluginRegistry pluginRegistry,
                                final DataCenterCache dataCenterCache,
                                final DataCenterStatusCache dataCenterStatusCache,
                                final StatefulsetCache statefulsetCache,
                                final K8sResourceUtils k8sResourceUtils,
                                final SidecarConnectionCache sidecarConnectionCache,
                                final MeterRegistry meterRegistry,
                                final ReaperPlugin reaperPlugin) {
        this.reconcilierObserver = reconcilierObserver;
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.pluginRegistry = pluginRegistry;
        this.cqlRoleManager = cqlRoleManager;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterStatusCache = dataCenterStatusCache;
        this.statefulsetCache = statefulsetCache;
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.meterRegistry = meterRegistry;
        this.reaperPlugin = reaperPlugin;
    }

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

    /**
     * Called when the DC CRD is updated, involving a rolling update of sts.
     */
    public Completable initDatacenter(DataCenter dc, Operation op) throws Exception {
        return reconcile(dc,
                statefulsetCache.loadIfAbsent(dc)
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
                    .withSubmitDate(new Date())
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
                                        .withSubmitDate(new Date())
                                        .withTriggeredBy("dc-after-task-" + task.getMetadata().getName())))
                .flatMapCompletable(dataCenterUpdateAction ->  dataCenterUpdateAction.taskDone(task))
                .andThen(k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus()).ignoreElement()));
    }

    /**
     * Fetch datacenters of the same cluster in the same namespace to automatically add remoteSeeders
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
