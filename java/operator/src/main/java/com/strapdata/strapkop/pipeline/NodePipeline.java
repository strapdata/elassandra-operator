package com.strapdata.strapkop.pipeline;

import com.squareup.okhttp.Call;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.cache.NodeCache;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ListMeta;
import io.kubernetes.client.models.V1Node;
import io.kubernetes.client.models.V1NodeList;
import io.kubernetes.client.models.V1ObjectMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

//@Context
//@Infrastructure
public class NodePipeline extends K8sWatchPipeline<V1Node, V1NodeList> {

    private final Logger logger = LoggerFactory.getLogger(NodePipeline.class);

    public NodePipeline(ApiClient apiClient, CoreV1Api coreV1Api, NodeCache cache, OperatorConfig config) {
        super(apiClient, new NodeAdapter(coreV1Api), cache, config);
    }
    
    private static class NodeAdapter extends K8sWatchResourceAdapter<V1Node, V1NodeList> {
    
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
        public Call createListApiCall(boolean watch, String resourceVersion) throws ApiException {
            return coreV1Api.listNodeCall(null, null, null, null,
                    null, null, resourceVersion, null, watch
                    , null, null);
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
