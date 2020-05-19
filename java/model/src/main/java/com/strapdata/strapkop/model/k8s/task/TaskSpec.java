package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
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

    @SerializedName("repair")
    @Expose
    private RepairTaskSpec repair;

    @SerializedName("rebuild")
    @Expose
    private RebuildTaskSpec rebuild;

    @SerializedName("elasticReset")
    @Expose
    private ElasticResetTaskSpec elasticReset;

    @SerializedName("removeNodes")
    @Expose
    private RemoveNodesTaskSpec removeNodes;

    @SerializedName("replication")
    @Expose
    private ReplicationTaskSpec replication;

    @SerializedName("backup")
    @Expose
    private BackupTaskSpec backup;

    @SerializedName("test")
    @Expose
    private TestTaskSpec test;
}
