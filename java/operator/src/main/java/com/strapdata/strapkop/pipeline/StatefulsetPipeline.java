package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import okhttp3.Call;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class StatefulsetPipeline extends EventPipeline<K8sWatchEvent<V1StatefulSet>> {

    public StatefulsetPipeline(@Named("apiClient") ApiClient apiClient, AppsV1Api appsV1Api, OperatorConfig operatorConfig) {
        super(new K8sWatchEventSource<>(apiClient, new StatefulsetPipeline.StatefulsetAdapter(appsV1Api, operatorConfig)));
    }

    private static class StatefulsetAdapter extends K8sWatchResourceAdapter<V1StatefulSet, V1StatefulSetList, Key> {

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
        public Call createListApiCall(Boolean watch, String resourceVersion) throws ApiException {
            return appsV1Api.listNamespacedStatefulSetCall(config.getWatchNamespace(),
                    null, null, null, null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
                    null, resourceVersion, null, watch, null);
        }

        @Override
        public Key getKey(V1StatefulSet resource) {
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
