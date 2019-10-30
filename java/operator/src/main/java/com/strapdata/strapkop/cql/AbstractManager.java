package com.strapdata.strapkop.cql;

import com.strapdata.model.k8s.cassandra.DataCenter;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Manage T for datacenters in a map where the key is a unique datacenter name.
 * @param <T>
 */
public abstract class AbstractManager<T> {

    private final Map<String, Map<String, T>> ressources = new HashMap<>(); // per datacenter resources

    public AbstractManager() {
    }

    public Map<String, T> get(final DataCenter dataCenter) {
        return ressources.get(key(dataCenter));
    }

    public T get(final DataCenter dataCenter, String name) {
        Map<String, T> map = get(dataCenter);
        return map == null ? null : map.get(name);
    }

    public void addIfAbsent(final DataCenter dataCenter, String key, Supplier<T> valueSupplier) {
        ressources.compute(key(dataCenter), (k,v) -> {
            if (v == null)
                v = new HashMap<>();
            v.putIfAbsent(key, valueSupplier.get());
            return v;
        });
    }

    public void remove(final DataCenter dataCenter) {
        ressources.remove(key(dataCenter));
    }

    public void remove(final DataCenter dataCenter, String name) {
        Map<String, T> map = get(dataCenter);
        if (map != null)
            map.remove(name);
    }

    // per DC  unique key
    private String key(final DataCenter dataCenter) {
        return dataCenter.getMetadata().getNamespace()+"/"+dataCenter.getSpec().getClusterName()+"/"+dataCenter.getMetadata().getName();
    }

}
