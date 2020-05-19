package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.models.V1Node;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.List;

/**
 * Do nothing handler, just log node event (cached by NodePipeline)
 */
@Handler
public class NodeHandler extends TerminalHandler<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodeHandler.class);

    @Inject
    MeterRegistry meterRegistry;

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "node"));

    @PostConstruct
    public void initGauge() {
        meterRegistry.gauge("k8s.managed",  tags, managed);
    }

    @Override
    public void accept(K8sWatchEvent<V1Node> event) throws Exception {
        logger.trace("event={}", event);
        switch(event.getType()) {
            case INITIAL:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                managed++;
                meterRegistry.counter("k8s.event.init", tags).increment();
                break;

            case ADDED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                managed++;
                meterRegistry.counter("k8s.event.added", tags).increment();
                break;

            case MODIFIED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                break;

            case DELETED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed--;
                break;

            case ERROR:
                logger.warn("event type={}", event.getType());
                meterRegistry.counter("k8s.event.error", tags).increment();
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