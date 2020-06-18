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

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
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
public class ElassandraPodPipeline extends EventPipeline<K8sWatchEvent<V1Pod>> {

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodPipeline.class);

    public ElassandraPodPipeline(@Named("apiClient") ApiClient apiClient, CoreV1Api coreV1Api, OperatorConfig config) {
        super(new K8sWatchEventSource<>(apiClient, new PodAdapter(coreV1Api, config)));
    }

    private static class PodAdapter extends K8sWatchResourceAdapter<V1Pod, V1PodList, String> {

        private final CoreV1Api coreV1Api;
        private final OperatorConfig config;

        public PodAdapter(@Named("coreApi") CoreV1Api coreV1Api, OperatorConfig config) {
            this.coreV1Api = coreV1Api;
            this.config = config;
        }

        @Override
        public Type getResourceType() {
            return V1Pod.class;
        }

        @Override
        public Type getResourceListType() {
            return V1PodList.class;
        }

        @Override
        public Call createListApiCall(Boolean watch, String resourceVersion) throws ApiException {
            return coreV1Api.listNamespacedPodCall(config.getWatchNamespace(), null, null, null,
                    null, OperatorLabels.toSelector(ImmutableMap.of(
                            OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR,
                            OperatorLabels.APP, OperatorLabels.ELASSANDRA_APP
                    )),  null, resourceVersion, null, watch, null);
        }

        @Override
        public String getKey(V1Pod resource) {
            return resource.getMetadata().getName();
        }

        @Override
        public V1ObjectMeta getMetadata(V1Pod resource) {
            return resource.getMetadata();
        }

        @Override
        public Collection<? extends V1Pod> getListItems(V1PodList list) {
            return list.getItems();
        }

        @Override
        public V1ListMeta getListMetadata(V1PodList list) {
            return list.getMetadata();
        }
    }
}
