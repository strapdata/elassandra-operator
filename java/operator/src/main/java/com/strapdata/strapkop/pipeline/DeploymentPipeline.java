package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.StatefulsetCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.*;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Not Cached deployment pipeline
 */
@Context
@Infrastructure
public class DeploymentPipeline extends EventPipeline<K8sWatchEvent<V1Deployment>> {

    public DeploymentPipeline(@Named("apiClient") ApiClient apiClient, StatefulsetCache cache, AppsV1Api appsV1Api, OperatorConfig operatorConfig) {
        super(new K8sWatchEventSource<>(apiClient, new DeploymentAdapter(appsV1Api, operatorConfig), operatorConfig));
    }

    private static class DeploymentAdapter extends K8sWatchResourceAdapter<V1Deployment, V1DeploymentList, Key> {

        private final AppsV1Api appsV1Api;
        private final OperatorConfig config;

        public DeploymentAdapter(AppsV1Api appsV1Api, OperatorConfig config) {
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
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return appsV1Api.listNamespacedStatefulSetCall(config.getNamespace(),
                    null, null, null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
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
