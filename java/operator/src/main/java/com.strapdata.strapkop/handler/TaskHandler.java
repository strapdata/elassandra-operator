package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableMap;
import com.strapdata.model.ClusterKey;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskVisitor;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.reconcilier.BackupTaskReconcilier;
import com.strapdata.strapkop.reconcilier.TaskReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class TaskHandler extends TerminalHandler<K8sWatchEvent<Task<?,?>>> {
    
    private final Logger logger = LoggerFactory.getLogger(TaskHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);
    
    private  Map<Class<?>, TaskReconcilier<?>> taskReconcilierMap;

    private final WorkQueue workQueue;
    
    public TaskHandler(final ApplicationContext context, WorkQueue workQueue) {
        taskReconcilierMap = ImmutableMap.of(
          BackupTask.class, context.getBean(BackupTaskReconcilier.class)
        );
        this.workQueue = workQueue;
    }
    
    @Override
    public void accept(K8sWatchEvent<Task<?,?>> data) throws Exception {
        logger.info("processing a BackupTask event");
        
        final ClusterKey key = new ClusterKey(
                data.getResource().getSpec().getCluster(),
                data.getResource().getMetadata().getNamespace()
        );
        
        if (creationEventTypes.contains(data.getType())) {
            data.getResource().accept(new SubmitTaskVisitor(key));
        }
        else if (deletionEventTypes.contains(data.getType())) {
            data.getResource().accept(new CancelTaskVisitor(key));
        }
    }
    
    @SuppressWarnings("unchecked")
    private <TaskT extends Task<?,?>> TaskReconcilier<TaskT> getReconcilier(Class<TaskT> klass) {
        return (TaskReconcilier<TaskT>) taskReconcilierMap.get(klass);
    }
    
    private class SubmitTaskVisitor implements TaskVisitor {
    
        final ClusterKey key;
        
        public SubmitTaskVisitor(ClusterKey key) {
            this.key = key;
        }
    
        @Override
        public void visit(BackupTask backupTask) {
            workQueue.submit(key, getReconcilier(BackupTask.class).prepareSubmitRunnable(backupTask));
        }
    }
    
    private class CancelTaskVisitor implements TaskVisitor {
    
        final ClusterKey key;
    
        public CancelTaskVisitor(ClusterKey key) {
            this.key = key;
        }
        
        @Override
        public void visit(BackupTask backupTask) {
            workQueue.submit(key, getReconcilier(BackupTask.class).prepareCancelRunnable(backupTask));
        }
    }
}
