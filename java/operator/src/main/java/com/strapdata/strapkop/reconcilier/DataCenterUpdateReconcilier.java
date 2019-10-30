package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class DataCenterUpdateReconcilier extends Reconcilier<Key> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterUpdateReconcilier.class);
    
    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;

    private final PluginRegistry pluginRegistry;

    public DataCenterUpdateReconcilier(final ApplicationContext context,
                                       final K8sResourceUtils k8sResourceUtils,
                                       final CoreV1Api coreApi,
                                       final PluginRegistry pluginRegistry) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public Completable reconcile(final Key key) throws ApiException {
        // this is a "read-before-write" to ensure we are processing the latest resource version (otherwise, status update will failed with a 409 conflict)
        // TODO: maybe we can use the datacenter cache in a smart way.
        //      ...something like : when we update the dc status, we notify the cache to invalidate the data until we receive an update
        return k8sResourceUtils.readDatacenter(key)
                .flatMapCompletable(dc -> {
                    if (dc.getStatus() != null && Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.EXECUTING_TASK)) {
                        logger.debug("do not reconcile datacenter as a task is already being executed ({})", dc.getStatus().getCurrentTask());
                        return Completable.complete();
                    }
                    try {
                        // call the statefullset reconciliation  (before scaling up/down to properly stream data according to the adjusted RF)
                        logger.trace("processing a dc reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());

                        return context.createBean(DataCenterUpdateAction.class, dc)
                                .reconcileDataCenter()
                                .andThen(Completable.mergeArray(pluginRegistry.reconcileAll(dc)))
                                .andThen(k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement());
                    } catch (Exception e) {
                        logger.error("an error occurred while processing DataCenter update reconciliation for {}", key.getName(), e);
                        if (dc != null) {
                            if (dc.getStatus() == null) {
                                dc.setStatus(new DataCenterStatus());
                            }
                            dc.getStatus().setPhase(DataCenterPhase.ERROR);
                            dc.getStatus().setLastErrorMessage(e.getMessage());
                            k8sResourceUtils.updateDataCenterStatus(dc);
                        }
                        throw e;
                    }
                })
                .observeOn(Schedulers.io());
    }
    
    

}
