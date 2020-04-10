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

public abstract class CachedK8sWatchPipeline<ResourceT, ResourceListT, K> extends EventPipeline<K8sWatchEvent<ResourceT>> {

    private final Logger logger = LoggerFactory.getLogger(CachedK8sWatchPipeline.class);

    private final Cache<K, ResourceT> cache;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT, K> adapter;

    public CachedK8sWatchPipeline(@Named("apiClient") ApiClient apiClient,
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
        final K key;

        switch (event.getType()) {
            case ADDED:
            case MODIFIED:
            case INITIAL:
                key = adapter.getKey(event.getResource());
                cache.put(key, event.getResource());
                break;
            case DELETED:
            case ERROR:
                key = adapter.getKey(event.getResource());
                cache.remove(key);
                break;
        }

        logger.trace("cache={}", cache);
    }
}
