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

    // Do we have to lock the execution of other task/reconciliation ?
    @SerializedName("exclusive")
    @Expose
    private boolean exclusive = true;

    @SerializedName("cleanup")
    @Expose
    private CleanupTaskSpec cleanup;

    @SerializedName("repair")
    @Expose
    private RepairTaskSpec repair;

    @SerializedName("backup")
    @Expose
    private BackupTaskSpec backup;

    @SerializedName("test")
    @Expose
    private TestTaskSpec test;
}
