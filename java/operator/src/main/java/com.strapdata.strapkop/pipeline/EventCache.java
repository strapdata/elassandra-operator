package com.strapdata.strapkop.pipeline;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class EventCache<KeyT, DataT> {
    
    @AllArgsConstructor
    @Data
    private static class CacheEntry<DataT> {
        DataT data;
        boolean processed;
    }
    
    private ConcurrentMap<KeyT, CacheEntry<DataT>> cache = new ConcurrentHashMap<>();
    
    void insert(final Event<KeyT, DataT> event) {
        cache.put(event.getKey(), new CacheEntry<>(event.getData(), false));
    }
    
    DataT get(final KeyT key) {
        final CacheEntry<DataT> entry = cache.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Cannot access missing key " + key);
        }
        return entry.data;
    }
    
    boolean isProcessed(final KeyT key) {
        final CacheEntry<DataT> entry = cache.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Cannot access missing key " + key);
        }
        return entry.processed;
    }
    
    void process(final KeyT key) {
        if (cache.computeIfPresent(key, (_key, data) -> data.setProcessed(true)) == null) {
            throw new IllegalArgumentException("EventCache cannot process event for absent key " + key);
        }
    }
}
