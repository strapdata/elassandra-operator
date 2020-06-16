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

package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskSpec;
import io.vavr.Tuple;
import io.vavr.Tuple2;

import javax.inject.Singleton;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

// TODO: task webhook should validate spec not empty
@Singleton
public class TaskResolver {

    private final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> taskFamily;

    public TaskResolver(
            BackupTaskReconcilier backupTaskReconcilier,
            CleanupTaskReconcilier cleanupTaskReconcilier,
            RepairTaskReconcilier repairTaskReconcilier,
            ReplicationTaskReconcilier replicationTaskReconcilier,
            RebuildTaskReconcilier rebuildTaskReconcilier,
            RemoveNodesTaskReconcilier removeNodesTaskReconcilier,
            UpdateRoutingTaskReconcilier updateRoutingTaskReconcilier
    ) {
        taskFamily = ImmutableList.of(
                Tuple.of(backupTaskReconcilier, TaskSpec::getBackup),
                Tuple.of(cleanupTaskReconcilier, TaskSpec::getCleanup),
                Tuple.of(repairTaskReconcilier, TaskSpec::getRepair),
                Tuple.of(replicationTaskReconcilier, TaskSpec::getReplication),
                Tuple.of(removeNodesTaskReconcilier, TaskSpec::getRemoveNodes),
                Tuple.of(rebuildTaskReconcilier, TaskSpec::getRebuild),
                Tuple.of(updateRoutingTaskReconcilier, TaskSpec::getUpdateRouting)
                );
    }

    public TaskReconcilier getTaskReconcilier(Task task) {
        final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> candidates = taskFamily.stream()
                .filter(tuple -> tuple._2.apply(task.getSpec()) != null)
                .collect(Collectors.toList());

        if (candidates.size() == 1) {
            //handleWrongTaskType(event);
            return candidates.get(0)._1;
        }
        throw new UnsupportedOperationException("Task not supported");
    }
}
