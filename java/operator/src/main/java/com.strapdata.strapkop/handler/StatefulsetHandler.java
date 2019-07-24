package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
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
    private final DataCenterCache dataCenterCache;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;
    
    public StatefulsetHandler(WorkQueue workQueue, DataCenterCache dataCenterCache, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
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
        if (!data.getType().equals(DELETED) &&
                (!Objects.equals(sts.getStatus().getReplicas(), ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0))
                || !Objects.equals(ObjectUtils.defaultIfNull(sts.getStatus().getCurrentReplicas(), 0), sts.getStatus().getReplicas()))) {
            logger.info("sts is not ready, skipping");
            return ;
        }
    
        logger.info("sts is ready, triggering a dc reconciliation");
        
        final String dcName = sts.getMetadata().getLabels().get(OperatorLabels.PARENT);
        final DataCenter dc = dataCenterCache.get(new Key(dcName, sts.getMetadata().getNamespace()));
        if (dc == null) {
            logger.warn("triggering the dc reconciliation failed because the dc missed from the cache");
            return ;
        }
        
        workQueue.submit(new ClusterKey(dc), dataCenterReconcilier.prepareRunnable(dc));
    }
}