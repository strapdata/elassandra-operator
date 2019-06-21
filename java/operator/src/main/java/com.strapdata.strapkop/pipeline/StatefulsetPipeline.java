package com.strapdata.strapkop.pipeline;

import com.strapdata.model.Key;
import io.kubernetes.client.models.V1StatefulSet;
import io.kubernetes.client.models.V1StatefulSetList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

@Context
@Infrastructure
public class StatefulsetPipeline extends EventPipeline<Key, K8sWatchEventData<V1StatefulSet>> {

    private final Logger logger = LoggerFactory.getLogger(StatefulsetPipeline.class);
    
    public StatefulsetPipeline(@Named("statefulset-source")final K8sWatchEventSource<V1StatefulSet, V1StatefulSetList> source) {
        super(source);
    }
}
