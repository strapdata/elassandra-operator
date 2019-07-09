package com.strapdata.strapkop.source;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.squareup.okhttp.Call;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.util.Watch;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.ERROR;
import static com.strapdata.strapkop.event.K8sWatchEvent.Type.INITIAL;

@Singleton
@Infrastructure
@SuppressWarnings("UnstableApiUsage")
public class TaskEventSource implements EventSource<K8sWatchEvent<Task<?,?>>> {
    
    private final Logger logger = LoggerFactory.getLogger(TaskEventSource.class);
    
    // to add a new kind of task this map should be edited
    private static Map<String, Type> taskTypeMap = ImmutableMap.of("backup", BackupTask.class);
    
    private final ApiClient apiClient;
    private final Gson gson;
    private final CustomObjectsApi customObjectsApi;
    private final OperatorConfig config;
    
    private String lastResourceVersion = null;
    
    public TaskEventSource(final ApiClient apiClient, final CustomObjectsApi customObjectsApi, final OperatorConfig config) {
        this.apiClient = apiClient;
        this.gson = apiClient.getJSON().getGson();
        this.customObjectsApi = customObjectsApi;
        this.config = config;
    }
    
    /**
     * Create a cold observable containing the existing resources first, then watching for modifications
     *
     * @return a cold observable
     * @throws ApiException
     */
    @Override
    public Observable<K8sWatchEvent<Task<?,?>>> createObservable() throws ApiException {
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
    private Observable<K8sWatchEvent<Task<?,?>>> createInitialObservable() throws ApiException {
        logger.info("Fetching existing k8s resources synchronously : Task");
        final ApiResponse<TaskListHack> apiResponse = apiClient.execute(createApiCall(false, null), TaskListHack.class);
        
        
        // TODO: is it necessary to handle different response statuses here...
        final TaskListHack resourceList = apiResponse.getData();
        logger.info("Fetched {} existing {}", resourceList.items.size(), "Task");
        lastResourceVersion = resourceList.metadata.getResourceVersion();
        return Observable.fromIterable(resourceList.getItems()).map(resourceAsMap ->
                new K8sWatchEvent<>(INITIAL, deserializeTask(resourceAsMap))
        );
    }
    
    /**
     * Create a cold observable out of a k8s watch
     *
     * @return a cold observable
     * @throws ApiException
     */
    private Observable<K8sWatchEvent<Task<?,?>>> createWatchObservable() throws ApiException {
        logger.info("Creating k8s watch for resource : Task");
        final Watch<JsonObject> watch = Watch.createWatch(apiClient, createApiCall(true, lastResourceVersion),
                new TypeToken<Watch.Response<JsonObject>>() {
                }.getType());
        return Observable.fromIterable(watch).map(this::objectJsonToEvent);
    }
    
    /**
     * Transform a raw ObjectJson into a Event ready to be published by the observable
     *
     * @param response whatever ObjectJson returned by k8s api
     * @return the event
     */
    private K8sWatchEvent<Task<?,?>> objectJsonToEvent(Watch.Response<JsonObject> response) throws Exception {
        final K8sWatchEvent.Type type = K8sWatchEvent.Type.valueOf(response.type);
        Task<?,?> resource = null;
        
        if (type == ERROR) {
            logger.error("{} list watch failed with {}.", "Task", response.status);
        } else {
            resource = deserializeTask(response.object);
            lastResourceVersion = resource.getMetadata().getResourceVersion();
        }
        
        return new K8sWatchEvent<Task<?,?>>()
                .setType(type)
                .setResource(resource);
    }
    
    private Task<?,?> deserializeTask(JsonObject object) throws Exception {
        
        final String taskTypeName = object.getAsJsonObject("spec").get("type").getAsString();
        if (taskTypeName == null) {
            throw new Exception("missing task type");
        }
        final Type taskType = taskTypeMap.get(taskTypeName);
        if (taskType == null) {
            throw new Exception(String.format("unknown task type %s", taskTypeName));
        }
        return gson.fromJson(object, taskType);
    }
    
    private Call createApiCall(boolean watch, String resourceVersion) throws ApiException {
        return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                config.getNamespace(), "elassandra-tasks", null, null,
                resourceVersion, watch, null, null);
    }
    
    @Data
    @NoArgsConstructor
    private class TaskListHack {
        
        @SerializedName("apiVersion")
        @Expose
        private String apiVersion;
        @SerializedName("kind")
        @Expose
        private String kind;
        @SerializedName("metadata")
        @Expose
        private V1ListMeta metadata;
        @SerializedName("items")
        @Expose
        private List<JsonObject> items;
    }
}
