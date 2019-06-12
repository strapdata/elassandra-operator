package com.strapdata.strapkop.watch;

import com.squareup.okhttp.Call;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ConfigMap;
import io.kubernetes.client.models.V1ConfigMapList;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import java.util.Collection;

@Context
@Infrastructure
public class ConfigMapWatchService extends WatchService<V1ConfigMap, V1ConfigMapList> {

    private final CoreV1Api coreApi;
    
    public ConfigMapWatchService(ApiClient apiClient, OperatorConfig config, CoreV1Api coreApi) {
        super(apiClient, config);
        this.coreApi = coreApi;
    }
    
    @Override
    protected Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException {
        return coreApi.listNamespacedConfigMapCall(config.getNamespace(), null, null, continueToken, null, OperatorLabels.toSelector(OperatorLabels.MANAGED), null, resourceVersion, null, watch, null, null);
    }

    @Override
    protected Collection<? extends V1ConfigMap> resourceListItems(final V1ConfigMapList configMapList) {
        return configMapList.getItems();
    }

    @Override
    protected V1ListMeta resourceListMetadata(final V1ConfigMapList configMapList) {
        return configMapList.getMetadata();
    }

    @Override
    protected V1ObjectMeta resourceMetadata(final V1ConfigMap config) {
        return config.getMetadata();
    }
}