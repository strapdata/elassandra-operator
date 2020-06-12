package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.inject.Singleton;

@Singleton
public class DataCenterCache extends Cache<Key, DataCenter> {

    DataCenterCache(final MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "datacenter")), this);
    }

}
