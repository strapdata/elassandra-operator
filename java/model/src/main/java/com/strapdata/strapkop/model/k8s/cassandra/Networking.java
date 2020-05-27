package com.strapdata.strapkop.model.k8s.cassandra;

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
    @SerializedName("hostPortEnabled")
    @Expose
    private Boolean hostPortEnabled = true;

    /**
     * Enable hostNetwork, allowing to bind on host IP addresses.
     */
    @SerializedName("hostNetworkEnabled")
    @Expose
    private Boolean hostNetworkEnabled = false;

    /**
     * Enable external LoadBalancer for each Elassandra node.
     */
    @SerializedName("nodeLoadBalancerEnabled")
    @Expose
    private Boolean nodeLoadBalancerEnabled = false;
}
