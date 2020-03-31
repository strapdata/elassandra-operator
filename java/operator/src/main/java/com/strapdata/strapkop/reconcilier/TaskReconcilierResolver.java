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

@Singleton
public class TaskReconcilierResolver {

    private final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> taskFamily;

    public TaskReconcilierResolver(
            BackupTaskReconcilier backupTaskReconcilier,
            CleanupTaskReconcilier cleanupTaskReconcilier,
            RepairTaskReconcilier repairTaskReconcilier,
            ReplicationTaskReconcilier replicationTaskReconcilier,
            RebuildTaskReconcilier rebuildTaskReconcilier,
            RemoveNodesTaskReconcilier removeNodesTaskReconcilier
    ) {
        taskFamily = ImmutableList.of(
                Tuple.of(backupTaskReconcilier, TaskSpec::getBackup),
                Tuple.of(cleanupTaskReconcilier, TaskSpec::getCleanup),
                Tuple.of(repairTaskReconcilier, TaskSpec::getRepair),
                Tuple.of(replicationTaskReconcilier, TaskSpec::getReplication),
                Tuple.of(removeNodesTaskReconcilier, TaskSpec::getRemoveNodes),
                Tuple.of(rebuildTaskReconcilier, TaskSpec::getRebuild));
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
