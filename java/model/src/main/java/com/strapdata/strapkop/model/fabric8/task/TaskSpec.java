/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

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
