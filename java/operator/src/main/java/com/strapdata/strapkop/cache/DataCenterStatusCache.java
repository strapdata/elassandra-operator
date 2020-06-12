package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.inject.Singleton;

@Singleton
public class DataCenterStatusCache extends Cache<Key, DataCenterStatus> {

    DataCenterStatusCache(final MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "datacenterStatus")), this);
    }
}
