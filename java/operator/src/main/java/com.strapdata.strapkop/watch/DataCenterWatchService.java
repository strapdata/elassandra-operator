package com.strapdata.strapkop.watch;

import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.instaclustr.model.k8s.cassandra.DataCenterList;
import com.squareup.okhttp.Call;
import com.strapdata.strapkop.OperatorConfig;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

@Context
@Infrastructure
public class DataCenterWatchService extends WatchService<DataCenter, DataCenterList> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterWatchService.class);
    
    private CustomObjectsApi customObjectsApi;
    
    public DataCenterWatchService(final ApiClient apiClient, final OperatorConfig config, final CustomObjectsApi customObjectsApi) {
        super(apiClient, config);
        this.customObjectsApi = customObjectsApi;
    }
    
    @Override
    protected Call listResources(final String continueToken, final String resourceVersion, final boolean watch) throws ApiException {
        return customObjectsApi.listNamespacedCustomObjectCall("stable.strapdata.com", "v1", config.getNamespace(), "elassandra-datacenters", null, null, resourceVersion, watch, null, null);
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