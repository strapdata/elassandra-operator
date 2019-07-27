package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
public class DataCenterUpdateReconcilier extends Reconcilier<DataCenter> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterUpdateReconcilier.class);
    
    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;
    
    public DataCenterUpdateReconcilier(final ApplicationContext context, K8sResourceUtils k8sResourceUtils) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
    }
    
    @Override
    void reconcile(final DataCenter dc) {
        
        try {
            logger.debug("processing a dc reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
            context.createBean(DataCenterUpdateAction.class, k8sResourceUtils.freshenDataCenter(dc)).reconcileDataCenter();
        }
        catch (Exception e) {
            logger.error("an error occurred while processing DataCenter update reconciliation for {}", dc.getMetadata().getName(), e);
        }
    }
}
