package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Elasticsearch configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Elasticsearch {

    /**
     * Enable elasticsearch support.
     *
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Elasticsearch HTTP port
     */
    @SerializedName("httpPort")
    @Expose
    private Integer httpPort = 9200;

    /**
     * Elasticsearch Transport port
     */
    @SerializedName("transportPort")
    @Expose
    private Integer elasticsearchTransportPort = 9300;

    /**
     * Create a Load balancer service with external IP for Elasticsearch
     */
    @SerializedName("loadBalancerEnabled")
    @Expose
    private Boolean loadBalancerEnabled = false;

    /**
     * The LoadBalancer exposing cql + elasticsearch nodePorts
     */
    @SerializedName("loadBalancerIp")
    @Expose
    private String loadBalancerIp;

    /**
     * Enable Elasticsearch service ingress
     */
    @SerializedName("ingressEnabled")
    @Expose
    private Boolean ingressEnabled = false;

}
