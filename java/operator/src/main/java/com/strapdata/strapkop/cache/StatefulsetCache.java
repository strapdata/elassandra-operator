package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.Key;
import io.kubernetes.client.models.V1StatefulSet;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.inject.Singleton;

@Singleton
public class StatefulsetCache extends Cache<Key, V1StatefulSet> {

    StatefulsetCache(MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "statefulset")), this);
    }
}
