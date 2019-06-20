package com.strapdata.strapkop.pipeline;

import com.google.common.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterList;
import com.strapdata.strapkop.OperatorConfig;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collection;

@Factory
public class K8sWatchEventSourceFactory {
    
    private OperatorConfig config;
    private ApiClient apiClient;
    private CustomObjectsApi customObjectsApi;
    
    public K8sWatchEventSourceFactory(final OperatorConfig config, final ApiClient apiClient, final CustomObjectsApi customObjectsApi) {
        this.config = config;
        this.apiClient = apiClient;
        this.customObjectsApi = customObjectsApi;
    }
    
    @Bean
    @Singleton
    public K8sWatchEventSource<DataCenter, DataCenterList> dataCenterK8sWatchEventSource() {
        
        return new K8sWatchEventSource<>(apiClient, new K8sWatchResourceAdapter<DataCenter, DataCenterList>() {
            
            @Override
            public Type getResourceType() {
                return DataCenter.class;
            }
    
            @Override
            public Type getResourceListType() {
                return DataCenterList.class;
            }
    
            @Override
            public Call createApiCall(boolean watch, String resourceVersion) throws ApiException {
                return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                        config.getNamespace(), "elassandra-datacenters", null, null,
                        resourceVersion, watch, null, null);
            }
    
            @Override
            public Key getGroupingKey(DataCenter resource) {
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
        });
    }
}
