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

    @SerializedName("updateRouting")
    @Expose
    private UpdateRoutingTaskSpec updateRouting;

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
