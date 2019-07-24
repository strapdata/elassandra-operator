package com.strapdata.strapkop.workqueue;

import com.strapdata.model.ClusterKey;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Per elassandra cluster work queue to ensure operations are executed sequentially over a single cluster, to prevent
 * bizarre scenario (e.g scaling down while doing a backup)
 */
@Singleton
@Infrastructure
public class WorkQueue {
    
    private static final Logger logger = LoggerFactory.getLogger(WorkQueue.class);
    
    private final Map<ClusterKey, Subject<Runnable>> queues = new HashMap<>();
    
    /**
     * Submit a task in the sub-queue associated with the cluster key, creating it if does not exist yet
     * @param key
     * @param runnable
     */
    public void submit(final ClusterKey key, final Runnable runnable) {
        
        Subject<Runnable> queue = queues.get(key);
        
        if (queue == null) {
            queue = createQueue(key);
            queues.put(key, queue);
        }
        
        queue.onNext(runnable);
    }
    
    private Subject<Runnable> createQueue(final ClusterKey key) {
        final Subject<Runnable> queue = BehaviorSubject.<Runnable>create()
                .toSerialized(); // this make the subject thread safe (e.g can call onNext concurrently)
        
        Disposable disposable = queue.observeOn(Schedulers.io())
                // doOnError will be called if an error occurs within the subject (which is unlikely)
                .doOnError(throwable -> logger.error("error in work queue for cluster {}", key.getName(), throwable))
                // re subscribe the the subject in case it fails (which is unlikely)
                .retryWhen(errors -> errors.delay(1, TimeUnit.SECONDS))
                .subscribe(runnable -> {
                    try {
                        runnable.run();
                    }
                    catch (Exception e) {
                        logger.error("uncaught exception propagated to work queue for cluster {}", key.getName(), e);
                    }
                });
        return queue;
    }
    
    /**
     * Free the resource associated with the cluster queue
     * @param key
     */
    public void dispose(final ClusterKey key) {
        final Subject<Runnable> queue = queues.get(key);
        if (queue != null) {
            queues.remove(key);
            queue.onComplete();
        }
    }
}