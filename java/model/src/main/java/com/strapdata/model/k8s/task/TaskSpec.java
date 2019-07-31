package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskSpec {
    
    // tasks are always elassandra-cluster-scoped
    @SerializedName("cluster")
    @Expose
    private String cluster;

    // most tasks are limited to a datacenter
    @SerializedName("datacenter")
    @Expose
    private String datacenter;
    
    @SerializedName("cleanup")
    @Expose
    private CleanupTaskSpec cleanup;
    
    @SerializedName("backup")
    @Expose
    private BackupTaskSpec backup;
}
