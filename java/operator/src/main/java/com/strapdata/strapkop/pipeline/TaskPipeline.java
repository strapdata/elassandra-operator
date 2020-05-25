package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.TaskCache;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskList;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class TaskPipeline extends CachedK8sWatchPipeline<Task, TaskList, Key> {

    private final Logger logger = LoggerFactory.getLogger(TaskPipeline.class);

    public TaskPipeline(@Named("apiClient") ApiClient apiClient, TaskCache cache, CustomObjectsApi customObjectsApi, OperatorConfig config) {
        super(apiClient, new TaskAdapter(customObjectsApi, config), cache);
    }

    public static class TaskAdapter extends K8sWatchResourceAdapter<Task, TaskList, Key> {

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
            return customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                    config.getNamespace(), Task.PLURAL, null, null, null,
                    null, null, resourceVersion, null, watch, null);
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
