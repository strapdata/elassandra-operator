package com.strapdata.strapkop.watch;

import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.instaclustr.model.k8s.cassandra.DataCenterList;
import com.squareup.okhttp.Call;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Infrastructure;

import java.util.Collection;

@Infrastructure
public class DataCenterWatchService extends WatchService<DataCenter, DataCenterList> {
    private final CustomObjectsApi customObjectsApi;
    private final String namespace;

    public DataCenterWatchService(final ApiClient apiClient,
                                  final CustomObjectsApi customObjectsApi,
                                  final String namespace) {
        super(apiClient);
        this.customObjectsApi = customObjectsApi;
        this.namespace = namespace;
    }

    @Override
    protected Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException {
        return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1", namespace, "elassandra-datacenters", null, null, resourceVersion, watch, null, null);
    }

    @Override
    protected Collection<? extends DataCenter> resourceListItems(final DataCenterList dataCenterList) {
        return dataCenterList.getItems();
    }

    @Override
    protected V1ListMeta resourceListMetadata(final DataCenterList dataCenterList) {
        return dataCenterList.getMetadata();
    }

    @Override
    protected V1ObjectMeta resourceMetadata(final DataCenter dataCenter) {
        return dataCenter.getMetadata();
    }
}