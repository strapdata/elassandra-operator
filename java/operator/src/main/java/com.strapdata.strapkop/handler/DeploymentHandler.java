package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.event.K8sWatchEvent;
import io.kubernetes.client.models.V1Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// TODO: this pipeline is not used yet
@Handler
public class DeploymentHandler extends TerminalHandler<K8sWatchEvent<V1Deployment>> {
    
    private final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);
    
    
    public DeploymentHandler() {
    }
    
    @Override
    public void accept(K8sWatchEvent<V1Deployment> data) {
        logger.info("processing a deployment event");
        
        final V1Deployment deployment = data.getResource();
        
        //TODO: nothing implemented yet
    }
}