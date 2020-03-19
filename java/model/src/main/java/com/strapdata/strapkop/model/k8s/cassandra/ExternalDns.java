package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

/**
 * External DNS configuration.
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class ExternalDns {

    /**
     * Enable external DNS support.
     *
     */
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
    @SerializedName("root")
    @Expose
    private String root;

    /**
     * External dns domain;
     */
    @SerializedName("domain")
    @Expose
    private String domain;

    /**
     * External DNS ttl
     */
    @SerializedName("ttl")
    @Expose
    private Integer ttl = 300;

}
