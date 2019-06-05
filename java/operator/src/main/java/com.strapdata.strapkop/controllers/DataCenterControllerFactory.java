package com.strapdata.strapkop.controllers;

import com.instaclustr.model.Key;
import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.K8sResourceUtils;

import javax.inject.Inject;
import javax.inject.Singleton;


@Singleton
public class DataCenterControllerFactory {

    @Inject
    public K8sResourceUtils k8sResourceUtils;

    public DataCenterControllerFactory() {

    }

    public DataCenterReconciliationController reconciliationControllerForDataCenter(final DataCenter dataCenter) {
        return null;
    }

    public DataCenterDeletionController deletionControllerForDataCenter(final Key<DataCenter> dataCenter) {
        return null;
    }
}
