package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.k8s.task.BackupTaskList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.BackupCache;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class BackupPipeline extends K8sWatchPipeline<BackupTask, BackupTaskList> {
    
    public BackupPipeline(ApiClient apiClient, BackupCache cache, CustomObjectsApi customObjectsApi, OperatorConfig config) {
        super(apiClient, new BackupAdapter(customObjectsApi, config), cache);
    }
    
    private static class BackupAdapter extends K8sWatchResourceAdapter<BackupTask, BackupTaskList> {
        
        private final CustomObjectsApi customObjectsApi;
        private final OperatorConfig config;
        
        public BackupAdapter(CustomObjectsApi customObjectsApi, OperatorConfig config) {
            this.customObjectsApi = customObjectsApi;
            this.config = config;
        }
    
        @Override
        public Type getResourceType() {
            return BackupTask.class;
        }
    
        @Override
        public Type getResourceListType() {
            return BackupTaskList.class;
        }
    
        @Override
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                    config.getNamespace(), "elassandrabackups", null, null,
                    resourceVersion, watch, null, null);
        }
    
        @Override
        public Key getKey(BackupTask resource) {
            return new Key(getMetadata(resource));
        }
    
        @Override
        public V1ObjectMeta getMetadata(BackupTask resource) {
            return resource.getMetadata();
        }
    
        @Override
        public Collection<? extends BackupTask> getListItems(BackupTaskList list) {
            return list.getItems();
        }
    
        @Override
        public V1ListMeta getListMetadata(BackupTaskList list) {
            return list.getMetadata();
        }
    }
}
