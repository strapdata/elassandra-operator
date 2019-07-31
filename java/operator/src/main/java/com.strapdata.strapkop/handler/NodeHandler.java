package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
import io.kubernetes.client.models.V1Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

/**
 * Do nothing handler for k8s nodes
 */
@Handler
public class NodeHandler extends TerminalHandler<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodeHandler.class);

    private static final EnumSet<K8sWatchEvent.Type> acceptedEventTypes = EnumSet.of(MODIFIED, INITIAL, DELETED);

    private final WorkQueue workQueue;
    private final DataCenterCache dataCenterCache;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;

    public NodeHandler(WorkQueue workQueue, DataCenterCache dataCenterCache, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<V1Node> data) throws Exception {
        if (!acceptedEventTypes.contains(data.getType())) {
            return ;
        }

        final V1Node node = data.getResource();
        logger.info("processing an event node={}", node.getMetadata().getName());
    }
}