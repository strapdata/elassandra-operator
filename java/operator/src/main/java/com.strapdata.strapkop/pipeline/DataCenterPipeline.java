package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class DataCenterPipeline extends K8sWatchPipeline<DataCenter, DataCenterList> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterPipeline.class);
    
    public DataCenterPipeline(ApiClient apiClient, DataCenterCache cache, CustomObjectsApi customObjectsApi, OperatorConfig config) {
        super(apiClient, new DataCenterAdapter(customObjectsApi, config), cache);
    }
    
    public static class DataCenterAdapter extends K8sWatchResourceAdapter<DataCenter, DataCenterList> {
        
        private final CustomObjectsApi customObjectsApi;
        private final OperatorConfig config;
        
        public DataCenterAdapter(CustomObjectsApi customObjectsApi, OperatorConfig config) {
            this.customObjectsApi = customObjectsApi;
            this.config = config;
        }
    
        @Override
        public Type getResourceType() {
            return DataCenter.class;
        }
    
        @Override
        public Type getResourceListType() {
            return DataCenterList.class;
        }
    
        @Override
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                    config.getNamespace(), "elassandra-datacenters", null, null,
                    resourceVersion, watch, null, null);
        }
  
        @Override
        public Key getKey(DataCenter resource) {
            return new Key(getMetadata(resource));
        }
    
        @Override
        public V1ObjectMeta getMetadata(DataCenter resource) {
            return resource.getMetadata();
        }
    
        @Override
        public Collection<? extends DataCenter> getListItems(DataCenterList list) {
            return list.getItems();
        }
    
        @Override
        public V1ListMeta getListMetadata(DataCenterList list) {
            return list.getMetadata();
        }
    }
}
