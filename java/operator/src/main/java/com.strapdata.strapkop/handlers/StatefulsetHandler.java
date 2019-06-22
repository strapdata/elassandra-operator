package com.strapdata.strapkop.handlers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.pipelines.K8sResourceCache;
import com.strapdata.strapkop.pipelines.K8sWatchEvent;
import com.strapdata.strapkop.reconciliers.DataCenterReconcilier;
import io.kubernetes.client.models.V1StatefulSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.util.EnumSet;
import java.util.Objects;

import static com.strapdata.strapkop.pipelines.K8sWatchEvent.Type.*;

@Handler
public class StatefulsetHandler extends TerminalHandler<K8sWatchEvent<V1StatefulSet>> {
    
    private final Logger logger = LoggerFactory.getLogger(StatefulsetHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> acceptedEventTypes = EnumSet.of(MODIFIED, INITIAL);
    
    private final K8sResourceCache<DataCenter> dataCenterCache;
    private final DataCenterReconcilier dataCenterReconcilier;
    
    public StatefulsetHandler(@Named("datacenter-cache") K8sResourceCache<DataCenter> dataCenterCache, DataCenterReconcilier dataCenterReconcilier) {
        this.dataCenterCache = dataCenterCache;
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<V1StatefulSet> data) throws Exception {
        if (!acceptedEventTypes.contains(data.getType())) {
            return ;
        }

        logger.info("processing a sts event");
        
        final V1StatefulSet sts = data.getResource();

        // abort if the sts scaling up/down replicas
        if (!Objects.equals(sts.getStatus().getReplicas(), sts.getStatus().getReadyReplicas())
                || !Objects.equals(sts.getStatus().getCurrentReplicas(), sts.getStatus().getReplicas())) {
            logger.info("sts is not ready, skipping");
            return ;
        }
    
        logger.info("sts is ready, triggering a dc reconciliation");
        
        final String dcName = sts.getMetadata().getLabels().get(OperatorLabels.DATACENTER);
        final DataCenter dc = dataCenterCache.get(new Key(dcName, sts.getMetadata().getNamespace()));
        if (dc == null) {
            logger.warn("triggering the dc reconciliation failed because the dc missed from the cache");
            return ;
        }
        
        dataCenterReconcilier.enqueueUpdate(dc);
    }
}