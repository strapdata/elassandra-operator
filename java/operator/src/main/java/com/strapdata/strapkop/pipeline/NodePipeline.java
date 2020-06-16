/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.cache.NodeCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
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
public class NodePipeline extends EventPipeline<K8sWatchEvent<V1Node>> {

    private final Logger logger = LoggerFactory.getLogger(NodePipeline.class);

    public NodePipeline(@Named("apiClient") ApiClient apiClient, CoreV1Api coreV1Api, NodeCache cache) {
        super(new K8sWatchEventSource<>(apiClient, new NodeAdapter(coreV1Api)));
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
