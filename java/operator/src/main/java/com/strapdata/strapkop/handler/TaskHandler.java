package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskSpec;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.*;
import io.reactivex.Completable;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class TaskHandler extends TerminalHandler<K8sWatchEvent<Task>> {
    
    private final Logger logger = LoggerFactory.getLogger(TaskHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);
    
    private final WorkQueues workQueues;
    private final OperatorConfig operatorConfig;
    private final K8sResourceUtils k8sResourceUtils;

    private final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> taskFamily;
    
    public TaskHandler(WorkQueues workQueues,
                       OperatorConfig operatorConfig,
                       final K8sResourceUtils k8sResourceUtils,
                       BackupTaskReconcilier backupTaskReconcilier,
                       CleanupTaskReconcilier cleanupTaskReconcilier,
                       TestTaskReconcilier testTaskReconcilier,
                       RepairTaskReconcilier repairTaskReconcilier,
                       ReplicationTaskReconcilier replicationTaskReconcilier,
                       RebuildTaskReconcilier rebuildTaskReconcilier,
                       RemoveNodesTaskReconcilier removeNodesTaskReconcilier) {
     
        this.workQueues = workQueues;
        this.operatorConfig = operatorConfig;
        this.k8sResourceUtils = k8sResourceUtils;

        taskFamily = ImmutableList.of(
                Tuple.of(backupTaskReconcilier, TaskSpec::getBackup),
                Tuple.of(cleanupTaskReconcilier, TaskSpec::getCleanup),
                Tuple.of(repairTaskReconcilier, TaskSpec::getRepair),
                Tuple.of(replicationTaskReconcilier, TaskSpec::getReplication),
                Tuple.of(removeNodesTaskReconcilier, TaskSpec::getRemoveNodes),
                Tuple.of(rebuildTaskReconcilier, TaskSpec::getRebuild),
                Tuple.of(testTaskReconcilier, TaskSpec::getTest));
    }
    
    @Override
    public void accept(K8sWatchEvent<Task> event) throws Exception {
        logger.debug("Processing a Task event={}", event);
        
        final ClusterKey key = new ClusterKey(
                event.getResource().getSpec().getCluster(),
                event.getResource().getMetadata().getNamespace()
        );
        
        final List<Tuple2<TaskReconcilier, Function<TaskSpec, Object>>> candidates = taskFamily.stream()
                .filter(tuple -> tuple._2.apply(event.getResource().getSpec()) != null)
                .collect(Collectors.toList());
        
        if (candidates.size() != 1) {
            handleWrongTaskType(event);
            return ;
        }
        
        if (creationEventTypes.contains(event.getType())) {
            Task task = event.getResource();
            TaskStatus taskStatus = task.getStatus();
            if (taskStatus == null || taskStatus.getPhase() == null || !taskStatus.getPhase().isTerminated()) {
                // execute task
                workQueues.submit(key, candidates.get(0)._1.prepareSubmitCompletable(event.getResource()));
            } else {
                // purge old task.
                org.joda.time.DateTime creation = task.getMetadata().getCreationTimestamp();
                long retentionInstant = System.currentTimeMillis() - operatorConfig.getTaskRetention().getSeconds()*1000;
                if (creation.isBefore(retentionInstant)) {
                    logger.info("Delete old terminated task={}", task.id());
                    Completable delete = k8sResourceUtils.deleteTask(task.getMetadata()).ignoreElement();
                    workQueues.submit(key, delete);
                }
            }
        }
        else if (deletionEventTypes.contains(event.getType())) {
            // TODO: implement task cancellation
        }
    }
    
    private void handleWrongTaskType(K8sWatchEvent<Task> data) {
        logger.error("wrong task arguments for {}, no task reconcilier found", data.getResource().getMetadata().getName());
        // TODO: write message in task status
    }
    
}
