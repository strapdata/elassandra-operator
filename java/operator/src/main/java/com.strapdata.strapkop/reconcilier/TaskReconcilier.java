package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.task.Task;
import io.kubernetes.client.ApiException;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public abstract class TaskReconcilier<TaskT extends Task<?, ?>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    
    private enum Action {
        SUBMIT, CANCEL
    }
    
    private final Subject<Tuple2<Action, TaskT>> queue;
    
    public TaskReconcilier() {
        queue = BehaviorSubject.<Tuple2<Action, TaskT>>create().toSerialized();
        queue.observeOn(Schedulers.single())
                .doOnError(throwable -> {
                    logger.info("error in task reconciliation");
                    throwable.printStackTrace();
                })
                .retryWhen(errors -> errors.delay(1, TimeUnit.SECONDS))
                .subscribe(t -> {
                    try {
                        process(t);
                    }
                    catch (Exception e) {
                        logger.error("an error occurred while processing task {}", t, e);
                    }
                });
    }
    
    
    protected abstract void processSubmit(TaskT task) throws ApiException;
    protected abstract void processCancel(TaskT task);

    private void process(final Tuple2<Action, TaskT> item) throws Exception {
        
        final TaskT task = item._2;
        
        if (item._1.equals(Action.SUBMIT)) {
            logger.debug("processing a task submit request for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            processSubmit(task);
        }
        
        else if (item._1.equals(Action.CANCEL)) {
            logger.debug("processing a task cancel for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            processCancel(task);
        }
    }
    
    public void submit(TaskT task) {
        queue.onNext(new Tuple2<>(Action.SUBMIT, task));
    }
    
    public void cancel(TaskT task) {
        queue.onNext(new Tuple2<>(Action.CANCEL, task));
    }
}
