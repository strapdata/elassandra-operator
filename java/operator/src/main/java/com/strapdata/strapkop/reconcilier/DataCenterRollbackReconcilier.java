package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Date;

/**
 * Rollback to the last successfully applied dc state.
 */
@Singleton
public class DataCenterRollbackReconcilier extends Reconcilier<Key> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterRollbackReconcilier.class);

    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;

    private final PluginRegistry pluginRegistry;

    public DataCenterRollbackReconcilier(final ReconcilierObserver reconcilierObserver,
                                         ApplicationContext context,
                                         final K8sResourceUtils k8sResourceUtils,
                                         final CoreV1Api coreApi,
                                         final PluginRegistry pluginRegistry) {
        super(reconcilierObserver);
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.pluginRegistry = pluginRegistry;
    }

    @Override
    public Completable reconcile(final Key key) throws ApiException {
        // this is a "read-before-write" to ensure we are processing the latest resource version (otherwise, status update will failed with a 409 conflict)
        return k8sResourceUtils.readDatacenter(key)
                .flatMap(dc -> reconcilierObserver.onReconciliationBegin().toSingleDefault(dc))
                .flatMapCompletable(dc -> {
                    logger.debug("rollback dc={}/{}", dc.getMetadata().getName(), dc.getMetadata().getNamespace());

                    try {
                        // call the statefullset reconciliation  (before scaling up/down to properly stream data according to the adjusted RF)
                        logger.trace("processing a configuration rollback for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());

                        return context.createBean(DataCenterUpdateAction.class, dc).rollbackDataCenter(key);
                        // do not perform updateDatacenterStatus here to avoid collision with the update performed in the rollback method
                    } catch (Exception e) {
                        logger.error("an error occurred while processing Rollback for {}", dc.getMetadata().getName(), e);
                        if (dc != null) {
                            if (dc.getStatus() == null) {
                                dc.setStatus(new DataCenterStatus());
                            }
                            dc.getStatus().setLastError(e.toString());
                            dc.getStatus().setLastErrorTime(new Date());
                            k8sResourceUtils.updateDataCenterStatus(dc, dc.getStatus());
                        }
                        throw e;
                    }
                })
                .doOnError(t -> { if (!(t instanceof ReconcilierShutdownException)) reconcilierObserver.failedReconciliationAction(); })
                .doOnComplete(reconcilierObserver.endReconciliationAction())
                .observeOn(Schedulers.io());
    }

}
