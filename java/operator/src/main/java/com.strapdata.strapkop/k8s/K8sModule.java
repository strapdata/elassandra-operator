package com.strapdata.strapkop.k8s;

import com.google.inject.Provides;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.*;
import io.kubernetes.client.util.ClientBuilder;
import io.micronaut.context.annotation.Factory;

import javax.inject.Singleton;
import java.io.IOException;

@Factory
public class K8sModule {

    final ApiClient apiClient;

    public K8sModule() throws IOException {
        apiClient = ClientBuilder.standard().build();
    }

    @Provides
    @Singleton
    public CoreV1Api provideCoreV1Api() {
        return new CoreV1Api(apiClient);
    }

    @Provides
    @Singleton
    public ApiextensionsV1beta1Api providesApiExtensionsV1beta1Api() {
        return new ApiextensionsV1beta1Api(apiClient);
    }

    @Provides
    @Singleton
    public CustomObjectsApi provideCustomObjectsApi() {
        return new CustomObjectsApi(apiClient);
    }

    @Provides
    @Singleton
    public VersionApi provideVersionApi() {
        return new VersionApi(apiClient);
    }

    @Provides
    @Singleton
    public AppsV1beta2Api provideAppsV1beta2Api() {
        return new AppsV1beta2Api(apiClient);
    }


    public ApiClient getApiClient() {
        return this.apiClient;
    }

}
