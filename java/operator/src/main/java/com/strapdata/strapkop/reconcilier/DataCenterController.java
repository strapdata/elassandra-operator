package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.CheckPointCache;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.SidecarConnectionCache;
import com.strapdata.strapkop.cache.TaskCache;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.event.Pod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.cassandra.ReaperPhase;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.plugins.ReaperPlugin;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Deployment;
import io.kubernetes.client.models.V1StatefulSet;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * DC controller
 */
@Singleton
public class DataCenterController {

    private final Logger logger = LoggerFactory.getLogger(DataCenterController.class);

    private final ApplicationContext context;
    private final PluginRegistry pluginRegistry;
    private final CqlRoleManager cqlRoleManager;
    private final CheckPointCache checkPointCache;
    private final DataCenterCache dataCenterCache;
    private final SidecarConnectionCache sidecarConnectionCache;
    private final TaskCache taskCache;
    private final MeterRegistry meterRegistry;
    private final K8sResourceUtils k8sResourceUtils;
    private final ReconcilierObserver reconcilierObserver;
    private final ReaperPlugin reaperPlugin;

    public DataCenterController(final ReconcilierObserver reconcilierObserver,
                                final ApplicationContext context,
                                final CqlRoleManager cqlRoleManager,
                                final PluginRegistry pluginRegistry,
                                final CheckPointCache checkPointCache,
                                final DataCenterCache dataCenterCache,
                                final K8sResourceUtils k8sResourceUtils,
                                final SidecarConnectionCache sidecarConnectionCache,
                                final TaskCache taskCache,
                                final MeterRegistry meterRegistry,
                                final ReaperPlugin reaperPlugin) {
        this.reconcilierObserver = reconcilierObserver;
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.pluginRegistry = pluginRegistry;
        this.cqlRoleManager = cqlRoleManager;
        this.checkPointCache = checkPointCache;
        this.dataCenterCache = dataCenterCache;
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.taskCache = taskCache;
        this.meterRegistry = meterRegistry;
        this.reaperPlugin = reaperPlugin;
    }

    public Completable reconcile(DataCenter dataCenter, boolean withLock, Completable action) throws Exception {
        return reconcilierObserver.onReconciliationBegin().toSingleDefault(dataCenter)
                .flatMapCompletable(dc -> {
                    if (withLock && dc.getStatus() != null && dc.getStatus().getBlock().isLocked()) {
                        logger.info("datacenter={} Do not reconcile block reasons={} as a task is already being executed ({})",
                                dc.id(), dc.getStatus().getBlock().getReasons(), dc.getStatus().getCurrentTask());
                        return Completable.complete();
                    }
                    try {
                        // call the statefullset reconciliation  (before scaling up/down to properly stream data according to the adjusted RF)
                        logger.trace("datacenter={} processing a DC reconciliation", dc.id());
                        return action;
                    } catch (Exception e) {
                        logger.error("datacenter={} an error occurred while processing DataCenter update reconciliation", dc.id(), e);
                        if (dc != null) {
                            if (dc.getStatus() == null) {
                                dc.setStatus(new DataCenterStatus());
                            }
                            dc.getStatus().setLastError(e.toString());
                            dc.getStatus().setLastErrorTime(new Date());
                            return k8sResourceUtils.updateDataCenterStatus(dc).flatMapCompletable(o -> { throw e; });
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
    public Completable initDatacenter(DataCenter dc) throws Exception {
        return reconcile(dc, false,
                fetchExistingStatefulSetsByZone(dc)
                        .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dc).setStatefulSetTreeMap(stsMap))
                .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.initDatacenter()));
    }

    /**
     * Called when the DC CRD is updated, involving a rolling update of sts.
     */
    public Completable updateDatacenter(DataCenter dc) throws Exception {
        return reconcile(dc, true,
                fetchExistingStatefulSetsByZone(dc)
                        .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dc).setStatefulSetTreeMap(stsMap))
                .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.updateDatacenter()));
    }

    /**
     * Called when a rack statefulset has the desired number of ready pod.
     * DC Controller should do the next action to reach the desired state
     * 1 - apply the desired spec to all sts
     * 2 - scale up/down or park/unpark dc.
     */
    public Completable statefulsetUpdate(DataCenter dataCenter, V1StatefulSet sts) throws Exception {
        return reconcile(dataCenter, false,
                fetchExistingStatefulSetsByZone(dataCenter)
                        .map(stsMap -> context.createBean(DataCenterUpdateAction.class, dataCenter).setStatefulSetTreeMap(stsMap))
                .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.statefulsetUpdate(sts)));
    }

    public Completable deploymentAvailable(DataCenter dataCenter, V1Deployment deployment) throws Exception {
        String app = deployment.getMetadata().getLabels().get(OperatorLabels.APP);
        if ("reaper".equals(app)) {
            // notify the reaper pligin that deployment is available
            dataCenter.getStatus().setReaperPhase(ReaperPhase.RUNNING);
            return reconcile(dataCenter, false,
                    reaperPlugin.reconcile(dataCenter)
                    .flatMapCompletable(b -> k8sResourceUtils.updateDataCenterStatus(dataCenter).ignoreElement()));
        }
        return Completable.complete();
    }

    public Completable unschedulablePod(Pod pod) throws Exception {
        final Key key = new Key(pod.getParent(), pod.getNamespace());
        DataCenter dc = dataCenterCache.get(key);

        return reconcile(dc, false,
                Single.just(context.createBean(DataCenterUpdateAction.class, dc))
                        .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.unschedulablePod(pod)));
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
        return reconcile(dc, true,
                Single.just(context.createBean(DataCenterUpdateAction.class, dc))
                        .flatMapCompletable(dataCenterUpdateAction -> dataCenterUpdateAction.taskDone(task))
                        .andThen(k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement()));
    }

    /**
     * Fetch existing statefulsets from k8s api and sort then by zone name
     *
     * @return a map of zone name -> statefulset
     * @throws ApiException      if there is an error with k8s api
     * @throws StrapkopException if the statefulset has no RACK label or if two statefulsets has the same zone label
     */
    public Single<TreeMap<String, V1StatefulSet>> fetchExistingStatefulSetsByZone(DataCenter dc) throws ApiException, StrapkopException {
        return Single.fromCallable(new Callable<TreeMap<String, V1StatefulSet>>() {
            @Override
            public TreeMap<String, V1StatefulSet> call() throws Exception {
                final Iterable<V1StatefulSet> statefulSetsIterable = k8sResourceUtils.listNamespacedStatefulSets(
                        dc.getMetadata().getNamespace(), null,
                        OperatorLabels.toSelector(OperatorLabels.datacenter(dc)));

                final TreeMap<String, V1StatefulSet> result = new TreeMap<>();

                for (V1StatefulSet sts : statefulSetsIterable) {
                    final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);

                    if (zone == null) {
                        throw new StrapkopException(String.format(Locale.ROOT, "statefulset %s has no RACK label", sts.getMetadata().getName()));
                    }
                    if (result.containsKey(zone)) {
                        throw new StrapkopException(String.format(Locale.ROOT, "two statefulsets in the same zone=%s dc=%s", zone, dc.getMetadata().getName()));
                    }
                    result.put(zone, sts);
                }

                return result;
            }
        });
    }
}
