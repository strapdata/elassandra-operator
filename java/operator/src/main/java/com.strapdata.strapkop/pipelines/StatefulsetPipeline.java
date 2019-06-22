package com.strapdata.strapkop.pipelines;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1StatefulSet;
import io.kubernetes.client.models.V1StatefulSetList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class StatefulsetPipeline extends K8sWatchPipeline<V1StatefulSet, V1StatefulSetList> {

    private final Logger logger = LoggerFactory.getLogger(StatefulsetPipeline.class);
    
    public StatefulsetPipeline(ApiClient apiClient, @Named("statefulset-cache") K8sResourceCache<V1StatefulSet> cache, AppsV1Api appsV1Api, OperatorConfig config) {
        super(apiClient, new StatefulsetAdapter(appsV1Api, config), cache);
    }
    
    private static class StatefulsetAdapter extends K8sWatchResourceAdapter<V1StatefulSet, V1StatefulSetList> {
        
        private final AppsV1Api appsV1Api;
        private final OperatorConfig config;
        
        public StatefulsetAdapter(AppsV1Api appsV1Api, OperatorConfig config) {
            this.appsV1Api = appsV1Api;
            this.config = config;
        }
        
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
    }
}
