package com.strapdata.strapkop.controllers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.pipeline.DataCenterPipeline;
import com.strapdata.strapkop.pipeline.K8sWatchEventData;
import io.kubernetes.client.models.V1StatefulSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.EnumSet;
import java.util.Optional;

import static com.strapdata.strapkop.pipeline.K8sWatchEventData.Type.*;

@PipelineController
public class StatefulsetIntermediateController extends IntermediateController<K8sWatchEventData<V1StatefulSet>, DataCenter> {
    
    private final Logger logger = LoggerFactory.getLogger(StatefulsetIntermediateController.class);
    
    private static final EnumSet<K8sWatchEventData.Type> acceptedEventTypes = EnumSet.of(MODIFIED, INITIAL);
    
    private final DataCenterPipeline dataCenterPipeline;
    
    public StatefulsetIntermediateController(final DataCenterPipeline dataCenterPipeline) {
        this.dataCenterPipeline = dataCenterPipeline;
    }
    
    @Override
    public Optional<DataCenter> accept(K8sWatchEventData<V1StatefulSet> data) throws Exception {
        if (!acceptedEventTypes.contains(data.getType())) {
            return Optional.empty();
        }

        logger.info("processing a sts event");
        
        final V1StatefulSet sts = data.getResource();

        // abort if the sts scaling up/down replicas
        if (!sts.getStatus().getReplicas().equals(sts.getStatus().getReadyReplicas())
                || !sts.getStatus().getCurrentReplicas().equals(sts.getStatus().getReplicas())) {
            logger.info("sts is not ready, skipping");
            return Optional.empty();
        }
    
        logger.info("sts is ready, triggering a dc reconciliation");
        
        final String dcName = sts.getMetadata().getLabels().get(OperatorLabels.DATACENTER);
        DataCenter dc = dataCenterPipeline.getFromCache(new Key(dcName, sts.getMetadata().getNamespace())).map(K8sWatchEventData::getResource).orElse(null);
        if (dc == null) {
            logger.warn("triggering the dc reconciliation failed because the dc missed from the cache");
            return Optional.empty();
        }
        
        return Optional.of(dc);
    }
}