package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class DataCenterUpdateReconcilier extends Reconcilier<Key> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterUpdateReconcilier.class);
    
    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;
    private final CredentialsInitializer credentialsInitializer;
    
    public DataCenterUpdateReconcilier(final ApplicationContext context, K8sResourceUtils k8sResourceUtils, CredentialsInitializer credentialsInitializer) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.credentialsInitializer = credentialsInitializer;
    }
    
    @Override
    void reconcile(final Key key) {
        
        DataCenter dc = null;
        try {
            // this is a "read-before-write" to ensure we are processing the latest resource version (otherwise, status update will failed with a 409 conflict)
            // TODO: maybe we can use the datacenter cache in a smart way.
            //      ...something like : when we update the dc status, we notify the cache to invalidate the data until we receive an update
             dc = k8sResourceUtils.readDatacenter(key);
    
            // call the main reconciliation
            logger.debug("processing a dc reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
            context.createBean(DataCenterUpdateAction.class, k8sResourceUtils.freshenDataCenter(dc)).reconcileDataCenter();
    
            // setup credentials if necessary
            if (dc.getStatus() != null &&
                    Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING) &&
                    Objects.equals(dc.getSpec().getAuthentication(), Authentication.CASSANDRA) && (
                    Objects.equals(dc.getStatus().getCredentialsStatus(), CredentialsStatus.DEFAULT) ||
                            Objects.equals(dc.getStatus().getCredentialsStatus(), CredentialsStatus.UNKNOWN))) {
        
                logger.debug("triggering a credentials reconciliation");
                credentialsInitializer.initializeCredentials(dc);
            }

            // update status
            k8sResourceUtils.updateDataCenterStatus(dc);
        }
        catch (Exception e) {
            logger.error("an error occurred while processing DataCenter update reconciliation for {}", key.getName(), e);
            if (dc != null) {
                if (dc.getStatus() == null) {
                    dc.setStatus(new DataCenterStatus());
                }
                dc.getStatus().setPhase(DataCenterPhase.ERROR);
                dc.getStatus().setLastErrorMessage(e.getMessage());
            }
        }
    }
}
