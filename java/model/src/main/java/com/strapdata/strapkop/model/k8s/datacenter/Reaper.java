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
import java.util.List;
import java.util.Map;

/**
 * Cassandra reaper default configuration.
 */
@Data
@With
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
    private String jwtSecret = null;

    /**
     * Enable Cassandra Reaper support.
     *
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;

    /**
     * Enable Cassandra Reaper support.
     *
     */
    @SerializedName("loggingLevel")
    @Expose
    private String loggingLevel = "INFO";

    /**
     * Reaper ingress suffix concatened with "repaer-" and "reaper-admin-"
     * reaper-suffix
     * reaper-admin-suffix
     */
    @SerializedName("ingressSuffix")
    @Expose
    private String ingressSuffix = null;

    /**
     * Ingress annotations
     */
    @SerializedName("ingressAnnotations")
    @Expose
    private Map<String, String> ingressAnnotations = null;

    @SerializedName("scheduledRepairs")
    @Expose
    private List<ReaperScheduledRepair> scheduledRepairs = null;

    public String reaperFingerprint() {
        List<Object> acc = new ArrayList<>();

        // we exclude :
        // * Reaper config
        // * Kibana config
        // * parked attribute
        // * scheduledBackups (DC reconciliation is useless in this case, we only want to update Scheduler)
        acc.add(this);
        String json = GsonUtils.toJson(acc);
        String digest = DigestUtils.sha1Hex(json).substring(0,7);
        return digest;
    }
}
