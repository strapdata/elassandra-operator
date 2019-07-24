package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DataCenterStatus {
    
    
    @SerializedName("phase")
    @Expose
    private DataCenterPhase phase;
    
    @SerializedName("credentialsStatus")
    @Expose
    private CredentialsStatus credentialsStatus;
    
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
}