package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.*;

@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class DataCenterStatus {

    /**
     * Reconciliation block
     */
    @SerializedName("block")
    @Expose
    private Block block = new Block();

    /**
     * Current DC phase
     */
    @SerializedName("phase")
    @Expose
    private DataCenterPhase phase = DataCenterPhase.RUNNING;

    /**
     * Current DC heath
     */
    @SerializedName("health")
    @Expose
    private Health health = Health.UNKNOWN;

    @SerializedName("needCleanup")
    @Expose
    private Boolean needCleanup = false;

    /**
     * A datacenter is bootstrapped when at least one node has joined the datacenter.
     */
    @SerializedName("bootstrapped")
    @Expose
    private Boolean bootstrapped = false;


    @SerializedName("lastAction")
    @Expose
    private String lastAction = null;

    @SerializedName("lastActionTime")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date   lastActionTime = null;


    @SerializedName("lastError")
    @Expose
    private String lastError = null;

    @SerializedName("lastErrorTime")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date   lastErrorTime = null;

    /**
     * CQL connection status
     */
    @SerializedName("cqlStatus")
    @Expose
    private CqlStatus cqlStatus = CqlStatus.NOT_STARTED;
    
    @SerializedName("cqlStatusMessage")
    @Expose
    private String cqlStatusMessage = null;

    /**
     * Current config map spec fingerprint.
     */
    @SerializedName("configMapFingerPrint")
    @Expose
    private String configMapFingerPrint = null;

    @SerializedName("currentTask")
    @Expose
    private String currentTask = null;

    /**
     * Ordered availability zones
     */
    @SerializedName("zones")
    @Expose
    private List<String> zones = new ArrayList<>();

    /**
     * Number of replica ready in the underlying sts.
     */
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    /**
     * State of cassandra racks by zone index.
     */
    @SerializedName("rackStatuses")
    @Expose
    private TreeMap<Integer, RackStatus> rackStatuses = new TreeMap<>();

    /**
     * keyspace manager status
     */
    @SerializedName("keyspaceManagerStatus")
    private KeyspaceManagerStatus keyspaceManagerStatus = new KeyspaceManagerStatus();

    /**
     * Kibana deployed space names
     */
    @SerializedName("kibanaSpaceNames")
    private Set<String> kibanaSpaceNames = new HashSet<>();

    /**
     * Cassandra reaper status
     */
    @SerializedName("reaperPhase")
    private ReaperPhase reaperPhase = ReaperPhase.NONE;

    public Health health() {
        if (DataCenterPhase.PARKED.equals(this.phase))
            return Health.RED;

        long green = rackStatuses == null ? 0 : rackStatuses.values().stream().filter(r -> Health.GREEN.equals(r.getHealth())).count();
        long yellowOrRed = rackStatuses == null ? 0 : rackStatuses.values().stream().filter(r -> !Health.GREEN.equals(r.getHealth())).count();
        if (green > 0 && yellowOrRed == 0)
            return Health.GREEN;
        if (green > 0 && yellowOrRed == 1)
            return Health.YELLOW;
        return Health.RED;
    }
}