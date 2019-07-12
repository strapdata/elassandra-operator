package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.micronaut.context.ApplicationContext;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.concurrent.TimeUnit;

@Singleton
public class DataCenterReconcilier {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterReconcilier.class);
    
    private enum Action {
        UPDATE, DELETE
    }
    
    private final Subject<Tuple2<Action, DataCenter>> queue;
    private final ApplicationContext context;
    
    public DataCenterReconcilier(ApplicationContext context) {
        this.context = context;
        queue = BehaviorSubject.<Tuple2<Action, DataCenter>>create().toSerialized();
        queue.observeOn(Schedulers.single())
                .doOnError(throwable -> {
                    logger.info("error in dc reconciliation");
                    throwable.printStackTrace();
                })
                .retryWhen(errors -> errors.delay(1, TimeUnit.SECONDS))
                .subscribe(this::process);
    }
    
    public void enqueueUpdate(final DataCenter dc) {
        queue.onNext(new Tuple2<>(Action.UPDATE, dc));
    }
    public void enqueueDelete(final DataCenter dc) {
        queue.onNext(new Tuple2<>(Action.DELETE, dc));
    }
    
    private void process(final Tuple2<Action, DataCenter> task) throws Exception {
        final DataCenter dc = task._2;

        try {
    
            if (task._1.equals(Action.UPDATE)) {
                logger.debug("processing a dc reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
                context.createBean(DataCenterUpdateAction.class, dc).reconcileDataCenter();
            } else if (task._1.equals(Action.DELETE)) {
                logger.debug("processing a dc deletion for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
                context.createBean(DataCenterDeteteAction.class, dc).deleteDataCenter();
            }
        }
        catch (Exception e) {
            logger.error("an error occurred while processing DataCenter reconciliation {} for {}", task._1, dc.getMetadata().getName(), e);
        }
    }
}
