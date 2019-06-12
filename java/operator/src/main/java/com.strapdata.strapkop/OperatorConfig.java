package com.strapdata.strapkop;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;

import javax.inject.Named;
import javax.inject.Singleton;

@Factory
public class OperatorConfig {
    
    //TODO: operator should take a namespace to watch as parameter
    @Bean
    @Singleton
    @Named("namespace")
    public String provideNamespace() {
        return "default";
    }
}
