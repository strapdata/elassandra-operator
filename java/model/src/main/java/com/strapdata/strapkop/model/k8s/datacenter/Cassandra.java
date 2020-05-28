package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cassandra settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Cassandra {

    /**
     * Cassandra workload
     */
    @SerializedName("workload")
    @Expose
    private Workload workload = Workload.READ_WRITE;

    /**
     * Cassandra YAML configuration
     */
    @SerializedName("config")
    @Expose
    private Map<String, Object> config = new HashMap<>();

    /**
     * Enable cassandra/ldap authentication and authorization
     */
    @SerializedName("authentication")
    @Expose
    private Authentication authentication = Authentication.CASSANDRA;

    /**
     * Enable SSL support
     */
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;

    /**
     * Play cassandra commitlogs in a dedicated init container to avoid the liveness timeout endless loop.
     */
    @SerializedName("commitlogsInitContainer")
    @Expose
    private Boolean commitlogsInitContainer = false;

    /**
     *  Tell Cassandra to use the local IP address (INTERNAL_IP).
     *  May require a VPC  or VPN between the datacenters.
     */
    @SerializedName("snitchPreferLocal")
    @Expose
    private Boolean snitchPreferLocal = true;

    /**
     * Remote seed IP addresses.
     */
    @SerializedName("remoteSeeds")
    @Expose
    private List<String> remoteSeeds = new ArrayList<>();

    /**
     * List of URL providing dynamic seed list.
     */
    @SerializedName("remoteSeeders")
    @Expose
    private List<String> remoteSeeders = new ArrayList<>();

    /**
     * CQL native port (also hostPort)
     */
    @SerializedName("nativePort")
    @Expose
    private Integer nativePort = 39042;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("storagePort")
    @Expose
    private Integer storagePort = 37000;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("sslStoragePort")
    @Expose
    private Integer sslStoragePort = 37001;
}
