package com.strapdata.strapkop.handler;

import com.google.common.collect.Sets;
import com.strapdata.model.ClusterKey;
import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.NodeStatusEvent;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Handler
public class NodeStatusHandler extends TerminalHandler<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(NodeStatusHandler.class);
    
    private static final Set<NodeStatus> reconcileOperationModes = Sets.immutableEnumSet(
            // Reconcile when nodes switch to NORMAL. There may be pending scale operations that were
            // waiting for a healthy cluster.
            NodeStatus.NORMAL,
            
            // Reconcile when nodes have finished decommissioning. This will resume the StatefulSet
            // reconciliation.
            NodeStatus.DECOMMISSIONED
    );
    
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;
    private final DataCenterCache dataCenterCache;
    
    public NodeStatusHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterReconcilier, DataCenterCache dataCenterCache) {
        this.workQueue = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
        this.dataCenterCache = dataCenterCache;
    }
    
    @Override
    public void accept(NodeStatusEvent event) {
        logger.info("processing a NodeStatus event {} {} -> {}",
                event.getPod().getMetadata().getName(),
                event.getPreviousMode(), event.getCurrentMode());
        
        if (event.getCurrentMode() != null && reconcileOperationModes.contains(event.getCurrentMode())) {
            final String clusterName = event.getPod().getMetadata().getLabels().get(OperatorLabels.CLUSTER);
            logger.debug("triggering dc reconciliation because of a NodeStatus change");
            workQueue.submit(
                    new ClusterKey(clusterName, event.getDataCenterKey().getNamespace()),
                    dataCenterReconcilier.asCompletable(event.getDataCenterKey()));
        }
    }
}
