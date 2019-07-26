package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.CredentialsStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.reconcilier.CredentialsReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static com.strapdata.strapkop.event.K8sWatchEvent.creationEventTypes;

@Handler
public class CredentialsHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    
    private final WorkQueue workQueue;
    private final CredentialsReconcilier credentialsReconcilier;
    
    public CredentialsHandler(WorkQueue workQueue, CredentialsReconcilier credentialsReconcilier) {
        this.workQueue = workQueue;
        this.credentialsReconcilier = credentialsReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        logger.debug("processing a DataCenter event");
    
    
        if (!creationEventTypes.contains(event.getType())) {
            return ;
        }
        
        final DataCenter dc = event.getResource();
        
        if (dc.getStatus() != null &&
                Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING) &&
                Objects.equals(dc.getSpec().getAuthentication(), Authentication.CASSANDRA) && (
                Objects.equals(dc.getStatus().getCredentialsStatus(), CredentialsStatus.DEFAULT) ||
                        Objects.equals(dc.getStatus().getCredentialsStatus(), CredentialsStatus.UNKNOWN))) {
            
            logger.debug("submit a credentials reconciliation");
            workQueue.submit(new ClusterKey(dc), credentialsReconcilier.asCompletable(dc));
        }
    }
}
