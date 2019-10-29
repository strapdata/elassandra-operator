package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

@Handler
public class DeploymentHandler extends TerminalHandler<K8sWatchEvent<V1Deployment>> {
    
    private final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);
    
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;
    
    
    public DeploymentHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterUpdateReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterUpdateReconcilier = dataCenterUpdateReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<V1Deployment> event) throws ApiException {
        logger.debug("Processing a Deployment event={}", event);
        
        final V1Deployment deployment = event.getResource();
        
        final String dcResourceName = Objects.requireNonNull(deployment.getMetadata().getLabels().get(OperatorLabels.PARENT));
        
        final String clusterName = Objects.requireNonNull(deployment.getMetadata().getLabels().get(OperatorLabels.CLUSTER));
        
        workQueue.submit(
                new ClusterKey(clusterName, deployment.getMetadata().getNamespace()),
                dataCenterUpdateReconcilier.reconcile(new Key(dcResourceName, deployment.getMetadata().getNamespace()))
        );
    }
}