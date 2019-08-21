package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
public class DataCenterDeleteReconcilier extends Reconcilier<DataCenter> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterDeleteReconcilier.class);
    
    private final ApplicationContext context;
    private final CqlConnectionManager cqlConnectionManager;
    
    public DataCenterDeleteReconcilier(final ApplicationContext context, CqlConnectionManager cqlConnectionManager) {
        this.context = context;
        this.cqlConnectionManager = cqlConnectionManager;
    }
    
    @Override
    void reconcile(final DataCenter dc) {
        
        try {
            logger.debug("processing a dc delete reconciliation for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
            context.createBean(DataCenterDeleteAction.class, dc).deleteDataCenter();
            
            cqlConnectionManager.removeConnection(dc);
        }
        catch (Exception e) {
            logger.error("an error occurred while processing DataCenter update reconciliation for {}", dc.getMetadata().getName(), e);
        }
    }
}
