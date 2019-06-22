package com.strapdata.strapkop.pipelines;

import com.strapdata.model.Key;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class K8sResourceCache<ResourceT> {
    
    private ConcurrentMap<Key, ResourceT> cache = new ConcurrentHashMap<>();
    
    public ResourceT get(final Key key) {
        return cache.get(key);
    }
    
    /* package-private */ void insert(final Key key, final ResourceT resource) {
        cache.put(key, resource);
    }
    
    /* package-private */ ResourceT delete(final Key key) {
        return cache.remove(key);
    }
}
