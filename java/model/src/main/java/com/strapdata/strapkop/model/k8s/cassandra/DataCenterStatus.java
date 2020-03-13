package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
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
    private DataCenterPhase phase = DataCenterPhase.CREATING;

    @SerializedName("needCleanup")
    @Expose
    private Boolean needCleanup = false;

    /**
     * A datacenter is bootstrapped when at least one node has joined the datacenter.
     */
    @SerializedName("bootstrapped")
    @Expose
    private Boolean bootstrapped = false;

    @SerializedName("lastMessage")
    @Expose
    private String lastMessage = null;

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
     * Cassandra reaper status
     */
    @SerializedName("reaperPhase")
    private ReaperPhase reaperPhase = ReaperPhase.NONE;

    /**
     * keyspace manager status
     */
    @SerializedName("keyspaceManagerStatus")
    private KeyspaceManagerStatus keyspaceManagerStatus = new KeyspaceManagerStatus();

    /**
     * Kibana deployed spaces
     */
    @SerializedName("kibanaSpaces")
    private Set<String> kibanaSpaces = new HashSet<>();

}