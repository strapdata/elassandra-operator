package com.strapdata.strapkop.handler;

import com.google.common.collect.Sets;
import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.event.NodeStatusEvent;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

@Handler
public class ElassandraNodeStatusHandler extends TerminalHandler<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(ElassandraNodeStatusHandler.class);
    
    private static final Set<ElassandraNodeStatus> reconcileOperationModes = Sets.immutableEnumSet(
            // Reconcile when nodes switch to NORMAL. There may be pending scale operations that were
            // waiting for a healthy cluster.
            ElassandraNodeStatus.NORMAL,
            
            // Reconcile when nodes have finished decommissioning. This will resume the StatefulSet
            // reconciliation.
            ElassandraNodeStatus.DECOMMISSIONED
    );
    
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;
    
    public ElassandraNodeStatusHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(NodeStatusEvent event) {
        logger.info("Processing a ElassandraNodeStatus event {} {} -> {}",
                event.getPod().getName(),
                event.getPreviousMode(), event.getCurrentMode());
        
        if (event.getCurrentMode() != null && reconcileOperationModes.contains(event.getCurrentMode())) {
            final String clusterName = event.getPod().getCluster();
            logger.debug("triggering dc reconciliation because of a ElassandraPodCrdStatus change");
            workQueue.submit(
                    new ClusterKey(clusterName, event.getPod().getNamespace()),
                    dataCenterReconcilier.asCompletable(new Key(event.getPod().getParent(), event.getPod().getNamespace())));
        }
    }
}
