package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * External DNS configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class ExternalDns {

    /**
     * Enable external DNS support.
     *
     */
    @JsonPropertyDescription("Enable External DNS configuration")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Unique root for DNS hostname generation.
     * For cassandra seed nodes : cassandra-x-0.domain, cassandra-x-1.domain
     * For elasticsearch: elasticsearch-x.domain
     * For kibana: kibana-x.domain
     * For reaper: repear-x.domain
     */
    @JsonPropertyDescription("Unique root for DNS hostname generation:\n" +
            "For cassandra seed nodes : cassandra-x-0.domain, cassandra-x-1.domain\n" +
            "For elasticsearch: elasticsearch-x.domain\n" +
            "For kibana: kibana-x.domain\n" +
            "For reaper: repear-x.domain\n"
    )
    @SerializedName("root")
    @Expose
    private String root;

    /**
     * External dns domain;
     */
    @JsonPropertyDescription("External dns domain")
    @SerializedName("domain")
    @Expose
    private String domain;

    /**
     * External DNS ttl
     */
    @JsonPropertyDescription("External DNS ttl")
    @SerializedName("ttl")
    @Expose
    private Integer ttl = 300;

}
