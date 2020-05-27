package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterList;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class DataCenterPipeline extends CachedK8sWatchPipeline<DataCenter, DataCenterList, Key> {

    private static final Logger logger = LoggerFactory.getLogger(DataCenterPipeline.class);

    public DataCenterPipeline(@Named("apiClient") ApiClient apiClient, DataCenterCache cache, CustomObjectsApi customObjectsApi, OperatorConfig config) {
        super(apiClient, new DataCenterAdapter(customObjectsApi, config), cache);
    }

    public static class DataCenterAdapter extends K8sWatchResourceAdapter<DataCenter, DataCenterList, Key> {

        private final CustomObjectsApi customObjectsApi;
        private final OperatorConfig config;

        public DataCenterAdapter(CustomObjectsApi customObjectsApi, OperatorConfig config) {
            this.customObjectsApi = customObjectsApi;
            this.config = config;
        }

        @Override
        public Type getResourceType() {
            return DataCenter.class;
        }

        @Override
        public Type getResourceListType() {
            return DataCenterList.class;
        }

        @Override
        public Call createListApiCall(Boolean watch, String resourceVersion) throws ApiException {
            logger.trace("watch={} resourceVersion={}", watch, resourceVersion);
            return customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                    config.getNamespace(), DataCenter.PLURAL, null, null, null,
                    null, null, resourceVersion, null, watch, null);
        }

        @Override
        public Key getKey(DataCenter resource) {
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
    }
}