package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.NodeCache;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1Node;
import io.kubernetes.client.models.V1NodeList;
import io.kubernetes.client.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.Collection;

@Context
@Infrastructure
public class NodePipeline extends K8sWatchPipeline<V1Node, V1NodeList> {

    private final Logger logger = LoggerFactory.getLogger(NodePipeline.class);

    public NodePipeline(ApiClient apiClient, NodeCache cache, AppsV1Api appsV1Api, OperatorConfig config) {
        super(apiClient, new NodeAdapter(appsV1Api, config), cache);
    }
    
    private static class NodeAdapter extends K8sWatchResourceAdapter<V1Node, V1NodeList> {
        
        private final AppsV1Api appsV1Api;
        private final OperatorConfig config;
        
        public NodeAdapter(AppsV1Api appsV1Api, OperatorConfig config) {
            this.appsV1Api = appsV1Api;
            this.config = config;
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
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return appsV1Api.listNamespacedStatefulSetCall(config.getNamespace(),
                    null, null, null,
                    null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
                    null, resourceVersion, null, watch, null, null);
        }
        
        @Override
        public Key getKey(V1Node resource) {
            return new Key(getMetadata(resource));
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
