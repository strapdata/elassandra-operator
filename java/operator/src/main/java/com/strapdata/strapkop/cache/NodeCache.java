package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import io.kubernetes.client.models.V1Node;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.inject.Singleton;

@Singleton
public class NodeCache extends Cache<String, V1Node> {

    NodeCache(MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "node")), this);
    }
}
