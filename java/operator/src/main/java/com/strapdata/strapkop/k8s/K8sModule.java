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

package com.strapdata.strapkop.k8s;

import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.FileReader;
import java.io.IOException;

@Factory
public class K8sModule {

    private final ApiClient apiClient;
    private final ApiClient watchClient;
    private final ApiClient debuggableApiClient;

    public K8sModule(ApplicationContext applicationContext) throws IOException {

        if (applicationContext.getEnvironment().getActiveNames().contains("k8s")) {
            ApiClient apiClient = ClientBuilder.cluster().build();

            // set the global default api-client to the in-cluster one from above
            Configuration.setDefaultApiClient(apiClient);
        } else {
            // file path to your KubeConfig
            String kubeConfigPath = "~/.kube/config";

            // loading the out-of-cluster config, a kubeconfig from file-system
            ApiClient apiClient = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();

            // set the global default api-client to the in-cluster one from above
            Configuration.setDefaultApiClient(apiClient);
        }

        this.apiClient = ClientBuilder.standard().build();
        this.watchClient =  Config.defaultClient();

        // trick to debug k8s calls except for the Watch (not supported)
        if (System.getenv("K8S_API_DEBUG") != null) {
            debuggableApiClient = ClientBuilder.standard().build();
            debuggableApiClient.setDebugging(true);
        } else {
            debuggableApiClient = apiClient;
        }
    }

    @Bean
    @Singleton
    public SharedInformerFactory provideSharedInformerFactory() {
        return new SharedInformerFactory();
    }

    @Bean
    @Singleton
    public CoreV1Api provideCoreV1Api() {
        return new CoreV1Api(debuggableApiClient);
    }

    @Bean
    @Singleton
    public ApiextensionsV1beta1Api providesApiExtensionsV1beta1Api() {
        return new ApiextensionsV1beta1Api(apiClient);
    }

    @Bean
    @Singleton
    public CustomObjectsApi provideCustomObjectsApi() {
        return new CustomObjectsApi(apiClient);
    }

    @Bean
    @Singleton
    public ExtensionsV1beta1Api provideExtensionsV1beta1Api() {
        return new ExtensionsV1beta1Api(apiClient);
    }

    @Bean
    @Singleton
    public VersionApi provideVersionApi() {
        return new VersionApi(apiClient);
    }

    @Bean
    @Singleton
    public AppsV1beta2Api provideAppsV1beta2Api() {
        return new AppsV1beta2Api(apiClient);
    }

    @Bean
    @Singleton
    @Named("apiClient")
    public ApiClient provideApiClient() {
        return this.apiClient;
    }

    @Bean
    @Singleton
    @Named("watchClient")
    public ApiClient provideWatchClient() {
        return this.watchClient;
    }

    @Bean
    @Singleton
    @Named("debugApiClient")
    public ApiClient provideDebugApiClient() {
        return this.debuggableApiClient;
    }

    @Bean
    @Singleton
    public AppsV1Api provideAppsV1Api() {
        return new AppsV1Api(debuggableApiClient);
    }

    @Bean
    @Singleton
    @Named("policyApi")
    public PolicyV1beta1Api providePolicyV1beta1Api() { return new PolicyV1beta1Api(apiClient); }
}
