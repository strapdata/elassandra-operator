package com.strapdata.strapkop;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * This class holds a type-safe representation of the configuration gathered from props file (application.yaml)
 */
@ConfigurationProperties("operator")
@Getter
public class OperatorConfig {

    /**
     * Operator working namespace, listen on all namespaces if null
     */
    @Nullable
    String namespace;

    /**
     * Operator k8s service name used for callbacks
     */
    String serviceName;

    @Nullable
    int k8sWatchPeriodInSec = 200;

    @Nullable
    int elassandraNodeWatchPeriodInSec = 30;

    /**
     * Terminated task retention
     */
    Duration taskRetention = Duration.ofDays(8);

}
