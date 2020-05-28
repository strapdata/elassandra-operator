package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.HashMap;
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
    private Integer transportPort = 9300;

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

    /**
     * Elasticsearch YAML configuration map
     */
    @SerializedName("config")
    @Expose
    private Map<String, Object> config = new HashMap<>();

    /**
     * Elassandra datacenter group
     */
    @SerializedName("datacenterGroup")
    @Expose
    private String datacenterGroup = null;

    /**
     * Elassandra datacenter group
     */
    @SerializedName("datacenterTags")
    @Expose
    private List<String> datacenterTags = null;

    /**
     * Elassandra Enterprise configuration
     */
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
