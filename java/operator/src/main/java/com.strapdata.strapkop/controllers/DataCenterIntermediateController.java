package com.strapdata.strapkop.controllers;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.K8sWatchEventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Optional;

import static com.strapdata.strapkop.pipeline.K8sWatchEventData.Type.*;

@PipelineController
public class DataCenterIntermediateController extends IntermediateController<K8sWatchEventData<DataCenter>, DataCenter> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterIntermediateController.class);
    
    private static final EnumSet<K8sWatchEventData.Type> acceptedEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    
    @Override
    public Optional<DataCenter> accept(K8sWatchEventData<DataCenter> data) throws Exception {
        if (!acceptedEventTypes.contains(data.getType())) {
            return Optional.empty();
        }
    
        logger.info("processing a DataCenter reconciliation event");
        
        return Optional.of(data.getResource());
    }
}
