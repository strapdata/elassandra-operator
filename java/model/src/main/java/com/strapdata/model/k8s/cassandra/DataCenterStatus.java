package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class DataCenterStatus {
    
    
    @SerializedName("phase")
    @Expose
    private DataCenterPhase phase;
    
    @SerializedName("lastErrorMessage")
    @Expose
    private String lastErrorMessage = null;
    
    @SerializedName("credentialsStatus")
    @Expose
    private CredentialsStatus credentialsStatus = null;
    
    @SerializedName("cqlStatus")
    @Expose
    private CqlStatus cqlStatus = CqlStatus.NOT_STARTED;
    
    @SerializedName("cqlErrorMessage")
    @Expose
    private String cqlErrorMessage = null;
    
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 0;
    
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;
    
    @SerializedName("joinedReplicas")
    @Expose
    private Integer joinedReplicas = 0;
    
    @SerializedName("currentTask")
    @Expose
    private String currentTask;
    
    @SerializedName("rackStatuses")
    @Expose
    private List<RackStatus> rackStatuses;
    
    @SerializedName("podStatuses")
    @Expose
    private List<ElassandraPodStatus> podStatuses;
    
    @SerializedName("reaperKeyspaceInitialized")
    @Expose
    private Boolean reaperKeyspaceInitialized = false;
}