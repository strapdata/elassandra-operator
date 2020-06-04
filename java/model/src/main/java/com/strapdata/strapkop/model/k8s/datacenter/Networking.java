package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Network settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Networking {
    /**
     * Enable hostPort for nativePort, storagePort and sslStoragePort
     */
    @JsonPropertyDescription("Enable K8S hostPort")
    @SerializedName("hostPortEnabled")
    @Expose
    private Boolean hostPortEnabled = true;

    /**
     * Enable hostNetwork, allowing to bind on host IP addresses.
     */
    @JsonPropertyDescription("Enable K8S hostNetwork")
    @SerializedName("hostNetworkEnabled")
    @Expose
    private Boolean hostNetworkEnabled = false;

    /**
     * Enable external LoadBalancer for each Elassandra node.
     */
    @JsonPropertyDescription("Enable external LoadBalancer for each Elassandra node")
    @SerializedName("nodeLoadBalancerEnabled")
    @Expose
    private Boolean nodeLoadBalancerEnabled = false;
}
