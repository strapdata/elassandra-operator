package com.strapdata.strapkop.cql;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Manage T for datacenters in a map where the key is a unique datacenter name.
 * @param <T>
 */
public abstract class AbstractManager<T extends CqlReconciliable> {

    private final ConcurrentMap<String, Map<String, T>> resources = new ConcurrentHashMap<>(); // per datacenter resources

    final MeterRegistry meterRegistry;

    public AbstractManager(final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeMapSize("manager.size", ImmutableList.of(new ImmutableTag("type", getClass().getSimpleName())), resources);
    }

    public Map<String, T> get(final DataCenter dataCenter) {
        return resources.get(key(dataCenter));
    }

    public Map<String, T> get(String namespace, String clusterName, String datacenterName) {
        return resources.get(key(namespace, clusterName, datacenterName));
    }

    public Map<String, T> get(String dcKey) {
        return resources.get(dcKey);
    }


    public T get(final DataCenter dataCenter, String name) {
        Map<String, T> map = get(dataCenter);
        return map == null ? null : map.get(name);
    }

    public void put(final DataCenter dataCenter, String name, T t) {
        resources.compute(key(dataCenter), (k, v) -> {
            if (v == null)
                v = new HashMap<>();
            v.put(name, t);
            return v;
        });
    }

    public void addIfAbsent(final DataCenter dataCenter, String key, Supplier<T> valueSupplier) {
        resources.compute(key(dataCenter), (k, v) -> {
            if (v == null)
                v = new HashMap<>();
            v.computeIfAbsent(key, kk -> valueSupplier.get());
            return v;
        });
    }

    public void remove(final DataCenter dataCenter) {
        resources.remove(key(dataCenter));
    }

    public void remove(final DataCenter dataCenter, String name) {
        resources.compute(key(dataCenter), (k, v) -> {
            if (v != null)
                v.remove(name);
            return v;
        });
    }

    // per DC  unique key
    public String key(final DataCenter dataCenter) {
        return dataCenter.getMetadata().getNamespace()+"/"+dataCenter.getSpec().getClusterName()+"/"+dataCenter.getSpec().getDatacenterName();
    }

    public static String key(String namespace, String clusterName, String datacenterName) {
        return namespace+"/"+clusterName+"/"+datacenterName;
    }
}
