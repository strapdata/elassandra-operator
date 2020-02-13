package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1StatefulSet;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Objects;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class StatefulsetHandler extends TerminalHandler<K8sWatchEvent<V1StatefulSet>> {
    
    private final Logger logger = LoggerFactory.getLogger(StatefulsetHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> acceptedEventTypes = EnumSet.of(MODIFIED, INITIAL, DELETED);
    
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;
    
    public StatefulsetHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<V1StatefulSet> event) throws ApiException {
        if (!acceptedEventTypes.contains(event.getType())) {
            return ;
        }

        logger.debug("Processing a Statefulset event={}", event);
        
        final V1StatefulSet sts = event.getResource();

        // abort if the sts scaling up/down replicas
        if (!event.getType().equals(DELETED) &&
                (!Objects.equals(sts.getStatus().getReplicas(), ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0))
                || !Objects.equals(ObjectUtils.defaultIfNull(sts.getStatus().getCurrentReplicas(), 0), sts.getStatus().getReplicas()))) {
            logger.info("sts is not ready, skipping");
            return ;
        }
    
        logger.info("sts is ready, triggering a dc reconciliation");
        
        final String dcResourceName = sts.getMetadata().getLabels().get(OperatorLabels.PARENT);
        final String clusterName = sts.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
        
        workQueue.submit(
                new ClusterKey(clusterName, sts.getMetadata().getNamespace()),
                dataCenterReconcilier.reconcile((new Key(dcResourceName, sts.getMetadata().getNamespace()))));
    }
}