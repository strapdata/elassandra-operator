package com.strapdata.strapkop.k8s;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.*;
import io.kubernetes.client.util.ClientBuilder;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Singleton;
import java.io.IOException;

@Factory
public class K8sModule {

    private final ApiClient apiClient;

    public K8sModule() throws IOException {
        apiClient = ClientBuilder.standard().build();
    }
    
    @Bean
    @Singleton
    public CoreV1Api provideCoreV1Api() {
        return new CoreV1Api(apiClient);
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
    public ApiClient provideApiClient() {
        return this.apiClient;
    }
}
