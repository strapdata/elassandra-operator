package com.strapdata.strapkop.controllers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.micronaut.context.ApplicationContext;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class DataCenterControllerFactory {
    
    @Inject
    ApplicationContext context;
    
    public DataCenterReconciliationController createReconciliationController(DataCenter dataCenter) {
        return context.createBean(DataCenterReconciliationController.class, dataCenter);
    }
    
    public DataCenterDeletionController createDeletionController(Key<DataCenter> dataCenterKey) {
        return context.createBean(DataCenterDeletionController.class, dataCenterKey);
    }
}
