package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.List;
import java.util.UUID;

/**
 * Cassandra reaper default configuration.
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class Reaper {

    /**
     * Reaper docker image;
     */
    @SerializedName("image")
    @Expose
    private String image  = "thelastpickle/cassandra-reaper:1.4.8";

    /**
     * Reaper JWT secret
     */
    @SerializedName("jwtSecret")
    @Expose
    private String jwtSecret = UUID.randomUUID().toString();


    /**
     * Enable Cassandra Reaper support.
     *
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;

    /**
     * Reaper ingress suffix concatened with "repaer-" and "reaper-admin-"
     * reaper-suffix
     * reaper-admin-suffix
     */
    @SerializedName("ingressSuffix")
    @Expose
    private String ingressSuffix = null;

    @SerializedName("reaperScheduledRepairs")
    @Expose
    private List<ReaperScheduledRepair> reaperScheduledRepairs = null;

}
