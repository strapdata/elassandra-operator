package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.NodeCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeAddress;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Do nothing handler, just log node event (cached by NodePipeline)
 */
@Handler
public class NodeHandler extends TerminalHandler<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodeHandler.class);

    @Inject
    NodeCache nodeCache;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    OperatorConfig operatorConfig;

    AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "node"));

    @PostConstruct
    public void initGauge() {
        managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    public void updateCache(V1Node node) {
        nodeCache.put(node.getMetadata().getName(), node);
    }

    public Single<V1ConfigMap> updateTranslatorConfigMap() throws ApiException {
        final V1ConfigMap configMap = new V1ConfigMap()
                .metadata(new V1ObjectMeta()
                        .name("elassandra-operator-translator")
                        .namespace(operatorConfig.getOperatorNamespace())
                        .labels(ImmutableMap.of(
                                OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR,
                                OperatorLabels.APP, OperatorLabels.ELASSANDRA_APP))
                );
        for(V1Node node : nodeCache.values()) {
            String externalIp = null;
            String internalIp = null;
            for(V1NodeAddress v1NodeAddress : node.getStatus().getAddresses()) {
                if (v1NodeAddress.getType().equals("InternalIP"))
                    internalIp = v1NodeAddress.getAddress();
                if (v1NodeAddress.getType().equals("ExternalIP"))
                    externalIp = v1NodeAddress.getAddress();
            }
            if (externalIp != null)
                configMap.putDataItem(externalIp, internalIp);
        }
        return k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
    }

    @Override
    public void accept(K8sWatchEvent<V1Node> event) throws Exception {
        logger.trace("event={}", event);
        switch(event.getType()) {
            case INITIAL:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                managed.incrementAndGet();
                meterRegistry.counter("k8s.event.init", tags).increment();
                updateCache(event.getResource());
                break;

            case ADDED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                managed.incrementAndGet();
                meterRegistry.counter("k8s.event.added", tags).increment();
                updateCache(event.getResource());
                break;

            case MODIFIED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                updateCache(event.getResource());
                break;

            case DELETED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
                nodeCache.remove(event.getResource().getMetadata().getName());
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