package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1Pod;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class PodCache extends Cache<Key, V1Pod> {

    PodCache(MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "pod")), this);
    }

    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e ->
                Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.DATACENTER), dc.getSpec().getDatacenterName()) &&
                        Objects.equals(e.getValue().getMetadata().getLabels().get(OperatorLabels.CLUSTER), dc.getSpec().getClusterName()) &&
                        Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace()));
    }
}
