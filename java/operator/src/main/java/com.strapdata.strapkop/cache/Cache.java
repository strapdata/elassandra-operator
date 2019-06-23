package com.strapdata.strapkop.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Cache<KeyT, DataT> {
    
    private ConcurrentMap<KeyT, DataT> cache = new ConcurrentHashMap<>();
    
    public DataT get(final KeyT KeyT) {
        return cache.get(KeyT);
    }
    
    public void insert(final KeyT KeyT, final DataT resource) {
        cache.put(KeyT, resource);
    }
    
    public DataT delete(final KeyT KeyT) {
        return cache.remove(KeyT);
    }
    
    public Iterable<Map.Entry<KeyT, DataT>> list() {
        return cache.entrySet();
    }
}
