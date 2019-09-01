package com.strapdata.strapkop.cql;

import com.strapdata.model.k8s.cassandra.DataCenter;

import java.util.HashMap;
import java.util.Map;

/**
 * Manage T for datacenters
 * @param <T>
 */
public abstract class AbstractManager<T> {

    protected final CqlConnectionManager cqlConnectionManager;

    private final Map<String, Map<String, T>> ressources = new HashMap<>(); // per datacenter resources

    public AbstractManager(CqlConnectionManager cqlConnectionManager) {
        this.cqlConnectionManager = cqlConnectionManager;
    }

    public Map<String, T> get(final DataCenter dataCenter) {
        return ressources.get(key(dataCenter));
    }

    public T get(final DataCenter dataCenter, String name) {
        Map<String, T> map = get(dataCenter);
        return map == null ? null : map.get(name);
    }

    public void add(final DataCenter dataCenter, String key, final T t) {
        ressources.compute(key(dataCenter), (k,v) -> {
            if (v == null)
                v = new HashMap<>();
            v.put(key, t);
            return v;
        });
    }

    public void addIfAbsent(final DataCenter dataCenter, String key, final T t) {
        ressources.compute(key(dataCenter), (k,v) -> {
            if (v == null)
                v = new HashMap<>();
            v.putIfAbsent(key, t);
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
