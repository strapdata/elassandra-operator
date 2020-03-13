package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.NodeCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.models.V1Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Do nothing handler for k8s nodes
 */
@Handler
public class NodeHandler extends TerminalHandler<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodeHandler.class);

    private final NodeCache nodeCache;

    public NodeHandler(final NodeCache nodeCache) {
        this.nodeCache = nodeCache;
    }
    
    @Override
    public void accept(K8sWatchEvent<V1Node> event) throws Exception {
        final V1Node node = event.getResource();
        logger.info("Node type={} node={}", event.getType(), node.getMetadata().getName());

        switch(event.getType()) {
            case ADDED:
            case INITIAL:
            case MODIFIED:
                V1Node oldNode = this.nodeCache.put(node.getMetadata().getName(), node);
                logger.debug("update cache node={}", node.getMetadata().getName());
                break;

            case DELETED:
                this.nodeCache.remove(node.getMetadata().getName());
                logger.debug("remove cache node={}", node.getMetadata().getName());
                break;

            case ERROR:
                throw new IllegalStateException("node event error");
        }
    }

    public static String getZone(V1Node v1Node) {
        String zoneName = v1Node.getMetadata().getLabels().get(OperatorLabels.ZONE);
        if (zoneName == null)
            zoneName = v1Node.getMetadata().getLabels().get(OperatorLabels.TOPOLOGY_ZONE);
        return zoneName;
    }

    public static String getRegion(V1Node v1Node) {
        String region = v1Node.getMetadata().getLabels().get(OperatorLabels.REGION);
        if (region == null)
            region = v1Node.getMetadata().getLabels().get(OperatorLabels.TOPOLOGY_REGION);
        return region;
    }


}