package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.cache.NodeCache;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
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
public class NodePipeline extends CachedK8sWatchPipeline<V1Node, V1NodeList, String> {

    private final Logger logger = LoggerFactory.getLogger(NodePipeline.class);

    public NodePipeline(@Named("apiClient") ApiClient apiClient, CoreV1Api coreV1Api, NodeCache cache) {
        super(apiClient, new NodeAdapter(coreV1Api), cache);
    }

    private static class NodeAdapter extends K8sWatchResourceAdapter<V1Node, V1NodeList, String> {

        private final CoreV1Api coreV1Api;

        public NodeAdapter(@Named("coreApi") CoreV1Api coreV1Api) {
            this.coreV1Api = coreV1Api;
        }

        @Override
        public Type getResourceType() {
            return V1Node.class;
        }

        @Override
        public Type getResourceListType() {
            return V1NodeList.class;
        }

        @Override
        public Call createListApiCall(Boolean watch, String resourceVersion) throws ApiException {
            return coreV1Api.listNodeCall(null, null, null, null,
                    null, null,  resourceVersion, null, watch
                    , null);
        }

        @Override
        public String getKey(V1Node resource) {
            return resource.getMetadata().getName();
        }

        @Override
        public V1ObjectMeta getMetadata(V1Node resource) {
            return resource.getMetadata();
        }

        @Override
        public Collection<? extends V1Node> getListItems(V1NodeList list) {
            return list.getItems();
        }

        @Override
        public V1ListMeta getListMetadata(V1NodeList list) {
            return list.getMetadata();
        }
    }
}
