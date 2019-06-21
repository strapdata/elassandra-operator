package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1StatefulSet;
import io.kubernetes.client.models.V1StatefulSetList;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collection;

@Factory
public class K8sWatchEventSourceFactory {
    
    private final OperatorConfig config;
    private final ApiClient apiClient;
    private final CustomObjectsApi customObjectsApi;
    private final AppsV1Api appsV1Api;
    
    public K8sWatchEventSourceFactory(final OperatorConfig config, final ApiClient apiClient, final CustomObjectsApi customObjectsApi, final AppsV1Api appsV1Api) {
        this.config = config;
        this.apiClient = apiClient;
        this.customObjectsApi = customObjectsApi;
        this.appsV1Api = appsV1Api;
    }
    
    @Bean
    @Singleton
    @Named("datacenter-source")
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
    
    @Bean
    @Singleton
    @Named("statefulset-source")
    public K8sWatchEventSource<V1StatefulSet, V1StatefulSetList> statefulsetK8sWatchEventSource() {
        return new K8sWatchEventSource<>(apiClient, new K8sWatchResourceAdapter<V1StatefulSet, V1StatefulSetList>() {
            @Override
            public Type getResourceType() {
                return V1StatefulSet.class;
            }
    
            @Override
            public Type getResourceListType() {
                return V1StatefulSetList.class;
            }
    
            @Override
            public Call createApiCall(boolean watch, String resourceVersion) throws ApiException {
                return appsV1Api.listNamespacedStatefulSetCall(config.getNamespace(),
                        null, null, null,
                        null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
                        null, resourceVersion, null, watch, null, null);
            }
    
            @Override
            public Key getGroupingKey(V1StatefulSet resource) {
                return new Key(getMetadata(resource));
            }
    
            @Override
            public V1ObjectMeta getMetadata(V1StatefulSet resource) {
                return resource.getMetadata();
            }
    
            @Override
            public Collection<? extends V1StatefulSet> getListItems(V1StatefulSetList list) {
                return list.getItems();
            }
    
            @Override
            public V1ListMeta getListMetadata(V1StatefulSetList list) {
                return list.getMetadata();
            }
        });
    }
}
