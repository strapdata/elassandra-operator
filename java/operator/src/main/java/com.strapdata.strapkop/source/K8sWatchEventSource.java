package com.strapdata.strapkop.source;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.pipeline.K8sWatchResourceAdapter;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.util.Watch;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.ERROR;
import static com.strapdata.strapkop.event.K8sWatchEvent.Type.INITIAL;

/**
 * A Event source for kubernetes resources.
 *
 * @param <ResourceT>
 * @param <ResourceListT>
 */
@SuppressWarnings("UnstableApiUsage")
public class K8sWatchEventSource<ResourceT, ResourceListT>
        implements EventSource<K8sWatchEvent<ResourceT>> {
    
    private final Logger logger = LoggerFactory.getLogger(K8sWatchEventSource.class);
    
    private final ApiClient apiClient;
    private final K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter;
    private final Gson gson;
    
    private String lastResourceVersion = null;
    
    public K8sWatchEventSource(final ApiClient apiClient, final K8sWatchResourceAdapter<ResourceT, ResourceListT> adapter) {
        this.apiClient = apiClient;
        this.adapter = adapter;
        this.gson = apiClient.getJSON().getGson();
    }
    
    /**
     * Create a cold observable containing the existing resources first, then watching for modifications
     *
     * @return a cold observable
     * @throws ApiException
     */
    @Override
    public Observable<K8sWatchEvent<ResourceT>> createObservable() throws ApiException {
        
        logger.info("(re)creating k8s event observable for {}", this.getClass().getSimpleName());
        
        // if last resource version is not null, restart watching where we stopped
        if (lastResourceVersion != null) {
            return createWatchObservable();
        }
        
        // otherwise take a snapshot of the current state, then watch
        return Observable.concat(createInitialObservable(), createWatchObservable());
    }
    
    /**
     * Fetch initial existing resource and create a cold observable out of it
     *
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<K8sWatchEvent<ResourceT>> createInitialObservable() throws ApiException {
        logger.info("Fetching existing k8s resources synchronously : {}", adapter.getName());
        final ApiResponse<ResourceListT> apiResponse = apiClient.execute(adapter.createListApiCall(false, null), adapter.getResourceListType());
        // TODO: is it necessary to handle different response statuses here...
        final ResourceListT resourceList = apiResponse.getData();
        logger.info("Fetched {} existing {}", adapter.getListItems(resourceList).size(), adapter.getName());
        lastResourceVersion = adapter.getListMetadata(resourceList).getResourceVersion();
        return Observable.fromIterable(
                adapter.getListItems(resourceList)).map(resource -> new K8sWatchEvent<>(INITIAL, resource)
        );
    }
    
    /**
     * Create a cold observable out of a k8s watch
     *
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<K8sWatchEvent<ResourceT>> createWatchObservable() throws ApiException {
        logger.info("Creating k8s watch for resource : {}", adapter.getName());
        final Watch<JsonObject> watch = Watch.createWatch(apiClient, adapter.createListApiCall(true, lastResourceVersion),
                new TypeToken<Watch.Response<JsonObject>>() {
                }.getType());
        return Observable.fromIterable(watch)
                .observeOn(Schedulers.io()).observeOn(Schedulers.io()) // blocking io seemed to happen on computational thread...
                .map(this::objectJsonToEvent).doFinally(watch::close);
    }
    
    /**
     * Transform a raw ObjectJson into a Event ready to be published by the observable
     *
     * @param response whatever ObjectJson returned by k8s api
     * @return the event
     */
    private K8sWatchEvent<ResourceT> objectJsonToEvent(Watch.Response<JsonObject> response) {
        final K8sWatchEvent.Type type = K8sWatchEvent.Type.valueOf(response.type);
        ResourceT resource = null;
        
        if (type == ERROR) {
            logger.error("{} list watch failed with {}.", adapter.getName(), response.status);
        } else {
            resource = gson.fromJson(response.object, adapter.getResourceType());
            lastResourceVersion = adapter.getMetadata(resource).getResourceVersion();
        }
        
        return new K8sWatchEvent<ResourceT>()
                .setType(type)
                .setResource(resource);
    }
}
