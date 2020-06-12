package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Elasticsearch {

    @JsonPropertyDescription("Enable Elasticsearch, default is true")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    @JsonPropertyDescription("Elasticsearch HTTP port, default is 9200")
    @SerializedName("httpPort")
    @Expose
    private Integer httpPort = 9200;

    @JsonPropertyDescription("Elasticsearch transport port, default is 9300")
    @SerializedName("transportPort")
    @Expose
    private Integer transportPort = 9300;

    @JsonPropertyDescription("Create a Load balancer service with external IP for Elasticsearch, default is false")
    @SerializedName("loadBalancerEnabled")
    @Expose
    private Boolean loadBalancerEnabled = false;

    /**
     * The LoadBalancer exposing cql + elasticsearch nodePorts
     */
    @JsonPropertyDescription("Add a LoadBalancer exposing CQL and elasticsearch HTTP nodePorts")
    @SerializedName("loadBalancerIp")
    @Expose
    private String loadBalancerIp;

    /**
     * Enable Elasticsearch service ingress
     */
    @JsonPropertyDescription("Enable Elasticsearch service ingress")
    @SerializedName("ingressEnabled")
    @Expose
    private Boolean ingressEnabled = false;

    /**
     * Ingress annotations
     */
    @JsonPropertyDescription("Elasticsearch ingress annotations")
    @SerializedName("ingressAnnotations")
    @Expose
    private Map<String, String> ingressAnnotations = null;

    /**
     * Elassandra datacenter group
     */
    @JsonPropertyDescription("Elassandra datacenter group")
    @SerializedName("datacenterGroup")
    @Expose
    private String datacenterGroup = null;

    /**
     * Elassandra datacenter tags
     */
    @JsonPropertyDescription("Elassandra datacenter tags")
    @SerializedName("datacenterTags")
    @Expose
    private List<String> datacenterTags = null;

    /**
     * Elassandra Enterprise configuration
     */
    @JsonPropertyDescription("Elassandra enterprise configuration")
    @SerializedName("enterprise")
    @Expose
    private Enterprise enterprise = new Enterprise()
            .setCbs(false)
            .setHttps(false)
            .setJmx(false)
            .setSsl(false)
            .setAaa(new Aaa().setEnabled(false));
}
