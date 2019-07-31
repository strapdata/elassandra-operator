package com.strapdata.strapkop.pipeline;

import com.strapdata.model.Key;
import com.strapdata.strapkop.cache.Cache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.source.K8sWatchEventSource;
import io.kubernetes.client.ApiClient;
import io.reactivex.Observable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class K8sWatchPipeline<ResourceT, ResourceListT> extends EventPipeline<K8sWatchEvent<ResourceT>> {

    private final Logger logger = LoggerFactory.getLogger(K8sWatchPipeline.class);
    
    private final Cache<Key, ResourceT> cache;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter;
    
    public K8sWatchPipeline(ApiClient apiClient, K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter, Cache<Key, ResourceT> cache) {
        super(new K8sWatchEventSource<>(apiClient, adapter));
        this.cache = cache;
        this.adapter = adapter;
    }
    
    @Override
    protected Observable<K8sWatchEvent<ResourceT>> decorate(Observable<K8sWatchEvent<ResourceT>> observable) {
        return observable.doOnNext(this::updateCache);
    }
    
    private void updateCache(K8sWatchEvent<ResourceT> event){
        final Key key = adapter.getKey(event.getResource());
    
        switch (event.getType()) {
            case ADDED:
            case MODIFIED:
            case INITIAL:
                cache.put(key, event.getResource());
                break;
            case DELETED:
                cache.remove(key);
                break;
            case ERROR:
                break;
        }

        logger.trace("cache={}", cache);
    }
}
