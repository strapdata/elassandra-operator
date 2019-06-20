package com.strapdata.strapkop.controllers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.micronaut.context.ApplicationContext;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DataCenterActionFactory {
    
    @Inject
    ApplicationContext context;
    
    public DataCenterReconciliationAction createReconciliation(DataCenter dataCenter) {
        return context.createBean(DataCenterReconciliationAction.class, dataCenter);
    }
    
    public DataCenterDeletionAction createDeletion(Key dataCenterKey) {
        return context.createBean(DataCenterDeletionAction.class, dataCenterKey);
    }
}
