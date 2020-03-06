package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.event.K8sWatchEvent;
import io.kubernetes.client.models.V1Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Do nothing handler for k8s nodes
 */
@Handler
public class NodeHandler extends TerminalHandler<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodeHandler.class);
    
    public NodeHandler() {
    }
    
    @Override
    public void accept(K8sWatchEvent<V1Node> event) throws Exception {
        final V1Node node = event.getResource();
        logger.debug("Processing an event type={} node={}", event.getType(), node.getMetadata().getName());
    }
}