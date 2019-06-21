package com.strapdata.strapkop.controllers;

import com.strapdata.model.k8s.cassandra.DataCenter;

@PipelineController
public class DataCenterReconciliationController extends TerminalController<DataCenter> {
    
    private DataCenterActionFactory dataCenterActionFactory;
    
    public DataCenterReconciliationController(DataCenterActionFactory dataCenterActionFactory) {
        this.dataCenterActionFactory = dataCenterActionFactory;
    }
    
    @Override
    protected void accept(DataCenter dc) throws Exception {
        dataCenterActionFactory.createReconciliation(dc).reconcileDataCenter();
    }
}
