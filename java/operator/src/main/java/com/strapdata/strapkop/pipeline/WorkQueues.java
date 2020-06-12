package com.strapdata.strapkop.pipeline;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per elassandra cluster work queue to ensure operations are executed sequentially over a single cluster, to prevent
 * bizarre scenario (e.g scaling down while doing a backup)
 */
@Singleton
@Infrastructure
public class WorkQueues {

    private static final Logger logger = LoggerFactory.getLogger(WorkQueues.class);

    private final Map<ClusterKey, Subject<Reconciliable>> queues = new ConcurrentHashMap<>();
    private final Map<ClusterKey, BigInteger> ids = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry meterRegistry;

    @PostConstruct
    public void initGauge() {
        meterRegistry.gaugeMapSize("workqueues.size", ImmutableList.of(new ImmutableTag("type", "workqueues")), queues);
    }

    /**
     * Submit a task in the sub-queue associated with the cluster key, creating it if does not exist yet
     *
     * @param key
     * @param completable
     */
    public synchronized void submit(final ClusterKey key, String resourceVersion, Reconciliable.Kind kind, K8sWatchEvent.Type type, final Completable completable) {

        Reconciliable reconciliable = new Reconciliable()
                .withKind(kind)
                .withType(type)
                .withCompletable(completable)
                .withSubmitTime(System.currentTimeMillis())
                .withResourceVersion(resourceVersion);

        Subject<Reconciliable> queue = queues.computeIfAbsent(key, k -> createQueue(k));
        queue.onNext(reconciliable);
    }

    private Subject<Reconciliable> createQueue(final ClusterKey key) {

        logger.debug("creating workqueue for key {}", key);

        final Subject<Reconciliable> queue = BehaviorSubject.<Reconciliable>create()
                .toSerialized(); // this make the subject thread safe (e.g can call onNext concurrently)

        Disposable disposable = queue.observeOn(Schedulers.io()).subscribeOn(Schedulers.io())
                // doOnError will be called if an error occurs within the subject (which is unlikely)
                .doOnError(throwable -> logger.error("error in work queue for cluster " + key.getName(), throwable))
                .subscribe(reconciliable -> {
                            logger.debug("--- cluster={} resourceVersion={} kind={} type={}", key, reconciliable.getResourceVersion(), reconciliable.getKind(), reconciliable.getType());
                            reconciliable.setStartTime(System.currentTimeMillis());
                            Throwable e = reconciliable.getCompletable().blockingGet();
                            if (e == null) {
                                logger.debug("--- cluster={} resourceVersion={} kind={} type={} pending={}ms execution={}ms",
                                        key, reconciliable.getResourceVersion(),
                                        reconciliable.getKind(), reconciliable.getType(),
                                        reconciliable.getStartTime() - reconciliable.getSubmitTime(),
                                        System.currentTimeMillis() - reconciliable.getStartTime());
                            } else {
                                logger.warn("--- cluster=" + key + " reconciliable=" + reconciliable + " error:", e);
                            }
                        });
        return queue;
    }

    /**
     * Free the resource associated with the cluster queue
     *
     * @param key
     */
    public void dispose(final ClusterKey key) {
        ids.remove(key);
        final Subject<Reconciliable> queue = queues.remove(key);
        if (queue != null) {
            queue.onComplete();
        }
    }
}