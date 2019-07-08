package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.task.BackupTask;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskVisitor;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.reconcilier.BackupTaskReconcilier;
import com.strapdata.strapkop.reconcilier.TaskReconcilier;
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

    
    public TaskHandler(final ApplicationContext context) {
        taskReconcilierMap = ImmutableMap.of(
          BackupTask.class, context.getBean(BackupTaskReconcilier.class)
        );
    }
    
    @Override
    public void accept(K8sWatchEvent<Task<?,?>> data) throws Exception {
        logger.info("processing a BackupTask event");
        
        if (creationEventTypes.contains(data.getType())) {
            data.getResource().accept(new SubmitTaskVisitor());
        }
        else if (deletionEventTypes.contains(data.getType())) {
            data.getResource().accept(new CancelTaskVisitor());
        }
    }
    
    @SuppressWarnings("unchecked")
    private <TaskT extends Task<?,?>> TaskReconcilier<TaskT> getReconcilier(Class<TaskT> klass) {
        return (TaskReconcilier<TaskT>) taskReconcilierMap.get(klass);
    }
    
    private class SubmitTaskVisitor implements TaskVisitor {
        
        @Override
        public void visit(BackupTask backupTask) {
            getReconcilier(BackupTask.class).submit(backupTask);
        }
    }
    
    private class CancelTaskVisitor implements TaskVisitor {
        
        @Override
        public void visit(BackupTask backupTask) {
            getReconcilier(BackupTask.class).cancel(backupTask);
        }
    }
}
