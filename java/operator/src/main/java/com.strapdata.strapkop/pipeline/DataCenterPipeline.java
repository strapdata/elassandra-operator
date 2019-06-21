package com.strapdata.strapkop.pipeline;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

@Context
@Infrastructure
public class DataCenterPipeline extends EventPipeline<Key, K8sWatchEventData<DataCenter>> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterPipeline.class);
    
    public DataCenterPipeline(@Named("datacenter-source") final K8sWatchEventSource<DataCenter, DataCenterList> source) {
        super(source);
    }
}
