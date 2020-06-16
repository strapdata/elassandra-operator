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


import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.K8sWatchEventSource;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentList;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import okhttp3.Call;

import javax.inject.Named;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Not Cached deployment pipeline
 */
@Context
@Infrastructure
public class DeploymentPipeline extends EventPipeline<K8sWatchEvent<V1Deployment>> {

    public DeploymentPipeline(@Named("apiClient") ApiClient apiClient, AppsV1Api appsV1Api, OperatorConfig operatorConfig) {
        super(new K8sWatchEventSource<>(apiClient, new DeploymentAdapter(appsV1Api, operatorConfig)));
    }

    private static class DeploymentAdapter extends K8sWatchResourceAdapter<V1Deployment, V1DeploymentList, Key> {

        private final AppsV1Api appsV1Api;
        private final OperatorConfig config;

        public DeploymentAdapter(AppsV1Api appsV1Api, OperatorConfig config) {
            this.appsV1Api = appsV1Api;
            this.config = config;
        }

        @Override
        public Type getResourceType() {
            return V1Deployment.class;
        }

        @Override
        public Type getResourceListType() {
            return V1DeploymentList.class;
        }

        @Override
        public Call createListApiCall(Boolean watch, String resourceVersion) throws ApiException {
            return appsV1Api.listNamespacedDeploymentCall(config.getWatchNamespace(),
                    null, null, null, null, OperatorLabels.toSelector(OperatorLabels.MANAGED),
                    null, resourceVersion, null, watch, null);
        }

        @Override
        public Key getKey(V1Deployment resource) {
            return new Key(getMetadata(resource));
        }

        @Override
        public V1ObjectMeta getMetadata(V1Deployment resource) {
            return resource.getMetadata();
        }

        @Override
        public Collection<? extends V1Deployment> getListItems(V1DeploymentList list) {
            return list.getItems();
        }

        @Override
        public V1ListMeta getListMetadata(V1DeploymentList list) {
            return list.getMetadata();
        }
    }
}
