package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Kibana configuration.
     *
     */
    @JsonPropertyDescription("Kibana configuration")
    @SerializedName("kibana")
    @Expose
    private Kibana kibana = new Kibana();

    public String kibanaFingerprint() {
        List<Object> acc = new ArrayList<>();

        // we exclude :
        // * Reaper config
        // * Kibana config
        // * parked attribute
        // * scheduledBackups (DC reconciliation is useless in this case, we only want to update Scheduler)
        acc.add(kibana);
        String json = GsonUtils.toJson(acc);
        String digest = DigestUtils.sha1Hex(json).substring(0,7);
        return digest;
    }
}
