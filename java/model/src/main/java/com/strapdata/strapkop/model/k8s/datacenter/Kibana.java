package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra reaper default configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Kibana {

    /**
     * Kibana docker image;
     */
    @JsonPropertyDescription("Kibana docker image")
    @SerializedName("image")
    @Expose
    private String image  = "docker.elastic.co/kibana/kibana-oss:6.2.3";

    /**
     * Enable Kibana support.
     *
     */
    @JsonPropertyDescription("Kibana enabled, default is true")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Kibana user spaces (key = space name)
     */
    @JsonPropertyDescription("Kibana user spaces")
    @SerializedName("spaces")
    @Expose
    private List<KibanaSpace> spaces = new ArrayList<>();
}
