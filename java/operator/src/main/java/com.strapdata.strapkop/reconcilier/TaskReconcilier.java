package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.task.Task;
import io.kubernetes.client.ApiException;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TaskReconcilier<TaskT extends Task<?, ?>> extends Reconcilier<Tuple2<TaskReconcilier.Action, TaskT>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract void processSubmit(TaskT task) throws ApiException;
    protected abstract void processCancel(TaskT task);

    @Override
    void process(final Tuple2<Action, TaskT> item) {
        
        final TaskT task = item._2;
        
        if (item._1.equals(Action.SUBMIT)) {
            logger.debug("processing a task submit request for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            try {
                processSubmit(task);
            } catch (ApiException e) {
                logger.error("error while submitting task {}", task.getMetadata().getName(), e);
            }
        }
        
        else if (item._1.equals(Action.CANCEL)) {
            logger.debug("processing a task cancel for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            processCancel(task);
        }
    }
    
    public Runnable prepareSubmitRunnable(TaskT task) {
        return this.prepareRunnable(new Tuple2<>(Action.SUBMIT, task));
    }
    
    public Runnable prepareCancelRunnable(TaskT task) {
        return this.prepareRunnable(new Tuple2<>(Action.CANCEL, task));
    }
}
