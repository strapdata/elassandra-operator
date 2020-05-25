package com.strapdata.strapkop.model.fabric8.task;


import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.TestTaskSpec;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.*;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@With
public class TaskSpec implements KubernetesResource {
    private String cluster;
    private String datacenter;
    private CleanupTaskSpec cleanup;
    private RepairTaskSpec repair;
    private RebuildTaskSpec rebuild;
    private ElasticResetTaskSpec elasticReset;
    private RemoveNodesTaskSpec removeNodes;
    private ReplicationTaskSpec replication;
    private DecommissionTaskSpec decommission;
    private BackupTaskSpec backup;
    private TestTaskSpec test;
}
