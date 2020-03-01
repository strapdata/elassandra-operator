package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra reaper default configuration.
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class Kibana {

    /**
     * Reaper docker image;
     */
    @SerializedName("image")
    @Expose
    private String image  = "docker.elastic.co/kibana/kibana-oss:6.2.3";

    /**
     * Enable Kibana support.
     *
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Kibana user spaces (key = space name)
     */
    @SerializedName("spaces")
    @Expose
    private List<KibanaSpace> spaces = new ArrayList<>();

    /**
     * Kibana ingress suffix (concatened with kibana spaces).
     * host: space-suffix
     */
    @SerializedName("ingressSuffix")
    @Expose
    private String ingressSuffix = null;

    /**
     * Kibana upgrade version for Elasticsearch 6.5+
     * Should be 1 starting with elasticsearch 6.8
     * See https://www.elastic.co/guide/en/kibana/current/upgrade-migrations.html
     */
    @SerializedName("version")
    @Expose
    private Integer version = null;
}
