package com.strapdata.strapkop.pipelines;

import com.strapdata.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1StatefulSet;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Named;
import javax.inject.Singleton;

@Factory
public class K8sResourceCacheFactory {
    
    @Singleton
    @Bean
    @Named("datacenter-cache")
    public K8sResourceCache<DataCenter> provideDataCenterCache() {
        return new K8sResourceCache<>();
    }
    
    @Singleton
    @Bean
    @Named("statefulset-cache")
    public K8sResourceCache<V1StatefulSet> provideStatefulsetCache() {
        return new K8sResourceCache<>();
    }
}
