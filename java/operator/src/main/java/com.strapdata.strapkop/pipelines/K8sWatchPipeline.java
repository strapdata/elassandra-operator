package com.strapdata.strapkop.pipelines;

import com.strapdata.model.Key;
import io.kubernetes.client.ApiClient;
import io.reactivex.Observable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class K8sWatchPipeline<ResourceT, ResourceListT> extends EventPipeline<K8sWatchEvent<ResourceT>> {

    private final Logger logger = LoggerFactory.getLogger(K8sWatchPipeline.class);
    
    private final K8sResourceCache<ResourceT> cache;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter;
    
    public K8sWatchPipeline(ApiClient apiClient, K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter, K8sResourceCache<ResourceT> cache) {
        super(new K8sWatchEventSource<>(apiClient, adapter));
        this.cache = cache;
        this.adapter = adapter;
    }
    
    @Override
    protected Observable<K8sWatchEvent<ResourceT>> decorate(Observable<K8sWatchEvent<ResourceT>> observable) {
        return observable.doOnNext(this::updateCache);
    }
    
    private void updateCache(K8sWatchEvent<ResourceT> event){
        final Key key = adapter.getGroupingKey(event.getResource());
    
        switch (event.getType()) {
            case ADDED:
            case MODIFIED:
            case INITIAL:
                cache.insert(key, event.getResource());
                break;
            case DELETED:
                cache.delete(key);
                break;
            case ERROR:
                break;
        }
    }
}
