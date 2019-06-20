package com.strapdata.strapkop.pipeline;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.strapdata.model.Key;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.util.Watch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

import static com.strapdata.strapkop.pipeline.K8sWatchEventData.Type.ERROR;
import static com.strapdata.strapkop.pipeline.K8sWatchEventData.Type.INITIAL;

/**
 * A Event source for kubernetes resources.
 * @param <ResourceT>
 * @param <ResourceListT>
 */
@SuppressWarnings("UnstableApiUsage")
public class K8sWatchEventSource<ResourceT, ResourceListT>
        implements EventSource<Key, K8sWatchEventData<ResourceT>> {
    
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
     * @return a cold observable
     * @throws ApiException
     */
    @Override
    public Observable<Event<Key, K8sWatchEventData<ResourceT>>> createObservable() throws ApiException {
        
        final Observable<Event<Key, K8sWatchEventData<ResourceT>>> initialObservable = createInitialObservable();
        final Observable<Event<Key, K8sWatchEventData<ResourceT>>> watchObservable = createWatchObservable();
        return Observable.concat(initialObservable, watchObservable);
    }
    
    /**
     * Fetch initial existing resource and create a cold observable out of it
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<Event<Key, K8sWatchEventData<ResourceT>>> createInitialObservable() throws ApiException {
        logger.info("Fetching existing k8s resources synchronously : {}", adapter.getName());
        final ApiResponse<ResourceListT> apiResponse = apiClient.execute(adapter.createApiCall(false, null), adapter.getResourceListType());
        // TODO: is it necessary to handle different response statuses here...
        final ResourceListT resourceList = apiResponse.getData();
        logger.info("Fetched {} existing {}", adapter.getListItems(resourceList).size(), adapter.getName());
        lastResourceVersion = adapter.getListMetadata(resourceList).getResourceVersion();
        return Observable.from(adapter.getListItems(resourceList)).map(resource ->
                new Event<>(adapter.getGroupingKey(resource), new K8sWatchEventData<>(INITIAL, resource)));
    }
    
    /**
     * Create a cold observable out of a k8s watch
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<Event<Key, K8sWatchEventData<ResourceT>>> createWatchObservable() throws ApiException {
        logger.info("Creating k8s watch for resource : {}", adapter.getName());
        final Watch<JsonObject> watch = Watch.createWatch(apiClient, adapter.createApiCall(true, lastResourceVersion),
                new TypeToken<Watch.Response<JsonObject>>() {}.getType());
        return Observable.from(watch).map(this::objectJsonToEvent);
    }
    
    /**
     * Transform a raw ObjectJson into a Event ready to be published by the observable
     * @param response whatever ObjectJson returned by k8s api
     * @return the event
     */
    private Event<Key, K8sWatchEventData<ResourceT>> objectJsonToEvent(Watch.Response<JsonObject> response) {
        final K8sWatchEventData.Type type = K8sWatchEventData.Type.valueOf(response.type);
        ResourceT resource = null;
        
        if (type == ERROR) {
            logger.error("{} list watch failed with {}.", adapter.getName(), response.status);
        } else {
            resource = gson.fromJson(response.object, adapter.getResourceType());
        }
        
        return new Event<Key, K8sWatchEventData<ResourceT>>()
                .setKey(adapter.getGroupingKey(resource))
                .setData(new K8sWatchEventData<ResourceT>()
                        .setType(type)
                        .setResource(resource));
    }
}
