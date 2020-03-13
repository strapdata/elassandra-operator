package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.Cache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
import io.kubernetes.client.ApiClient;
import io.reactivex.Observable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;

public abstract class K8sWatchPipeline<ResourceT, ResourceListT, K> extends EventPipeline<K8sWatchEvent<ResourceT>> {

    private final Logger logger = LoggerFactory.getLogger(K8sWatchPipeline.class);
    
    private final Cache<K, ResourceT> cache;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT, K> adapter;

    public K8sWatchPipeline(@Named("apiClient") ApiClient apiClient,
                            K8sWatchResourceAdapter<ResourceT, ResourceListT, K> adapter,
                            Cache<K, ResourceT> cache,
                            OperatorConfig operatorConfig) {
        super(new K8sWatchEventSource<>(apiClient, adapter, operatorConfig));
        this.cache = cache;
        this.adapter = adapter;
    }
    
    @Override
    protected Observable<K8sWatchEvent<ResourceT>> decorate(Observable<K8sWatchEvent<ResourceT>> observable) {
        return observable.doOnNext(this::updateCache);
    }
    
    private void updateCache(K8sWatchEvent<ResourceT> event){
        final K key = adapter.getKey(event.getResource());
    
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
