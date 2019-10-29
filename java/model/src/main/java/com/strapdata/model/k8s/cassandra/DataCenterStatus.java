package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
public class DataCenterStatus {

    /**
     * Current DC phase
     */
    @SerializedName("phase")
    @Expose
    private DataCenterPhase phase = DataCenterPhase.CREATING;

    @SerializedName("needCleanup")
    @Expose
    private Boolean needCleanup = false;


    @SerializedName("lastErrorMessage")
    @Expose
    private String lastErrorMessage = null;

    /**
     * CQL connection status
     */
    @SerializedName("cqlStatus")
    @Expose
    private CqlStatus cqlStatus = CqlStatus.NOT_STARTED;
    
    @SerializedName("cqlErrorMessage")
    @Expose
    private String cqlErrorMessage = null;

    /**
     * Number of nodes in the datacenter
     */
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 0;

    /**
     * Number of UN cassandra nodes in the datacenter
     */
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    /**
     * Number of cassandra joined node in the datacenter
     */
    @SerializedName("joinedReplicas")
    @Expose
    private Integer joinedReplicas = 0;

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
     * State of cassandra racks.
     */
    @SerializedName("rackStatuses")
    @Expose
    private List<RackStatus> rackStatuses = new ArrayList<>();

    /**
     * Cassandra node status of pods
     */
    @SerializedName("elassandraNodeStatuses")
    @Expose
    private Map<String, ElassandraNodeStatus> elassandraNodeStatuses = new HashMap<>();

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