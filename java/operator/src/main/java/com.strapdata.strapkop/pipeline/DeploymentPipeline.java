package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DeploymentCache;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.*;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collection;


// TODO: this pipeline is not used yet
@Context
@Infrastructure
public class DeploymentPipeline extends K8sWatchPipeline<V1Deployment, V1DeploymentList> {

    private final Logger logger = LoggerFactory.getLogger(DeploymentPipeline.class);
    
    public DeploymentPipeline(ApiClient apiClient, DeploymentCache cache, AppsV1Api appsV1Api, OperatorConfig config) {
        super(apiClient, new DeploymentAdapter(appsV1Api, config), cache);
    }
    
    private static class DeploymentAdapter extends K8sWatchResourceAdapter<V1Deployment, V1DeploymentList> {
        
        private final AppsV1Api appsV1Api;
        private final OperatorConfig config;
        
        public DeploymentAdapter(AppsV1Api appsV1Api, OperatorConfig config) {
            this.appsV1Api = appsV1Api;
            this.config = config;
        }
        
        @Override
        public Type getResourceType() {
            return V1Deployment.class;
        }
        
        @Override
        public Type getResourceListType() {
            return V1DeploymentList.class;
        }
        
        @Override
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return appsV1Api.listNamespacedDeploymentCall(config.getNamespace(),
                    null, null, null,
                    null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
                    null, resourceVersion, null, watch, null, null);
        }
        
        @Override
        public Key getKey(V1Deployment resource) {
            return new Key(getMetadata(resource));
        }
        
        @Override
        public V1ObjectMeta getMetadata(V1Deployment resource) {
            return resource.getMetadata();
        }
        
        @Override
        public Collection<? extends V1Deployment> getListItems(V1DeploymentList list) {
            return list.getItems();
        }
        
        @Override
        public V1ListMeta getListMetadata(V1DeploymentList list) {
            return list.getMetadata();
        }
    }
}
