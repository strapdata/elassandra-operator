package com.strapdata.strapkop.pipeline;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

@Context
@Infrastructure
public class DataCenterReconciliationPipeline extends EventPipeline<Key, DataCenter> {

    public DataCenterReconciliationPipeline(DatacenterReconciliationSource source) {
        super(source);
    }
}
