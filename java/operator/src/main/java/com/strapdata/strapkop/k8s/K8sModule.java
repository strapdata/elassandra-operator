package com.strapdata.strapkop.k8s;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.*;
import io.kubernetes.client.util.ClientBuilder;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;

@Factory
public class K8sModule {

    private final ApiClient apiClient;
    private final ApiClient debuggableApiClient;

    public K8sModule() throws IOException {
        apiClient = ClientBuilder.standard().build();

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
    @Named("debugApiClient")
    public ApiClient provideDebugApiClient() {
        return this.debuggableApiClient;
    }
    
    @Bean
    @Singleton
    public AppsV1Api provideAppsV1Api() {
        return new AppsV1Api(debuggableApiClient);
    }
}
