package com.strapdata.strapkop.watch;

import com.squareup.okhttp.Call;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1beta2Api;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1beta2StatefulSet;
import io.kubernetes.client.models.V1beta2StatefulSetList;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

import javax.inject.Named;
import java.util.Collection;

@Context
public class StatefulSetWatchService extends WatchService<V1beta2StatefulSet, V1beta2StatefulSetList> {
    private final AppsV1beta2Api appsApi;
    private final String namespace;

    public StatefulSetWatchService(final ApiClient apiClient,
                                   final AppsV1beta2Api appsApi,
                                   @Named("namespace") final String namespace) {
        super(apiClient);
        this.appsApi = appsApi;
        this.namespace = namespace;
    }

    @Override
    protected Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException {
        return appsApi.listNamespacedStatefulSetCall(namespace, null, null, continueToken, null, OperatorLabels.toSelector(OperatorLabels.MANAGED), null, resourceVersion, null, watch, null, null);
    }

    @Override
    protected Collection<? extends V1beta2StatefulSet> resourceListItems(final V1beta2StatefulSetList statefulSetList) {
        return statefulSetList.getItems();
    }

    @Override
    protected V1ListMeta resourceListMetadata(final V1beta2StatefulSetList statefulSetList) {
        return statefulSetList.getMetadata();
    }

    @Override
    protected V1ObjectMeta resourceMetadata(final V1beta2StatefulSet o) {
        return o.getMetadata();
    }
}