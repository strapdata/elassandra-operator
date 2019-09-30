package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.TaskCache;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class TaskPipeline extends K8sWatchPipeline<Task, TaskList> {

    private final Logger logger = LoggerFactory.getLogger(TaskPipeline.class);
    
    public TaskPipeline(@Named("apiClient") ApiClient apiClient, TaskCache cache, CustomObjectsApi customObjectsApi, OperatorConfig config) {
        super(apiClient, new TaskAdapter(customObjectsApi, config), cache);
    }
    
    public static class TaskAdapter extends K8sWatchResourceAdapter<Task, TaskList> {
        
        private final CustomObjectsApi customObjectsApi;
        private final OperatorConfig config;
        
        public TaskAdapter(CustomObjectsApi customObjectsApi, OperatorConfig config) {
            this.customObjectsApi = customObjectsApi;
            this.config = config;
        }
    
        @Override
        public Type getResourceType() {
            return Task.class;
        }
    
        @Override
        public Type getResourceListType() {
            return TaskList.class;
        }
    
        @Override
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                    config.getNamespace(), "elassandratasks", null, null,
                    resourceVersion, watch, null, null);
        }
  
        @Override
        public Key getKey(Task resource) {
            return new Key(getMetadata(resource));
        }
    
        @Override
        public V1ObjectMeta getMetadata(Task resource) {
            return resource.getMetadata();
        }
    
        @Override
        public Collection<? extends Task> getListItems(TaskList list) {
            return list.getItems();
        }
    
        @Override
        public V1ListMeta getListMetadata(TaskList list) {
            return list.getMetadata();
        }
    }
}
