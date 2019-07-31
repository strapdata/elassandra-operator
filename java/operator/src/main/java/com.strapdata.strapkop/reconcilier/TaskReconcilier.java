package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.task.Task;
import io.kubernetes.client.ApiException;
import io.reactivex.Completable;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TaskReconcilier extends Reconcilier<Tuple2<TaskReconcilier.Action, Task>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract void processSubmit(Task task) throws ApiException;
    protected abstract void processCancel(Task task);

    @Override
    void reconcile(final Tuple2<Action, Task> item) {
        
        final Task task = item._2;
        
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
    
    public Completable prepareSubmitRunnable(Task task) {
        return this.asCompletable(new Tuple2<>(Action.SUBMIT, task));
    }
    
    public Completable prepareCancelRunnable(Task task) {
        return this.asCompletable(new Tuple2<>(Action.CANCEL, task));
    }
}
