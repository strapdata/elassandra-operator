/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.pipeline;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
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

    private final Map<Key, Subject<Reconciliable>> queues = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry meterRegistry;

    final Scheduler scheduler;

    public WorkQueues(final MeterRegistry meterRegistry,
                      final ExecutorFactory executorFactory,
                      @Named("workqueue") UserExecutorConfiguration userExecutorConfiguration) {
        this.scheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeMapSize("workqueues.size", ImmutableList.of(new ImmutableTag("type", "workqueues")), queues);
    }

    /**
     * Submit a task in the sub-queue associated with the cluster key, creating it if does not exist yet
     *
     * @param key
     * @param completable
     */
    public synchronized void submit(final Key key, String resourceVersion, Reconciliable.Kind kind, K8sWatchEvent.Type type, final Completable completable) {
        Reconciliable reconciliable = new Reconciliable()
                .withKind(kind)
                .withType(type)
                .withCompletable(completable)
                .withSubmitTime(System.currentTimeMillis())
                .withResourceVersion(resourceVersion);

        Subject<Reconciliable> queue = queues.computeIfAbsent(key, k -> createQueue(k));
        queue.onNext(reconciliable);
    }

    private Subject<Reconciliable> createQueue(final Key key) {
        logger.debug("datacenter={} Creating workqueue", key.id());
        final Subject<Reconciliable> queue = BehaviorSubject.<Reconciliable>create()
                .toSerialized(); // this make the subject thread safe (e.g can call onNext concurrently)

        Disposable disposable = queue
                .observeOn(scheduler)
                .subscribe(reconciliable -> {
                            logger.debug("--- datacenter={} resourceVersion={} kind={} type={}",
                                    key.id(), reconciliable.getResourceVersion(), reconciliable.getKind(), reconciliable.getType());
                            reconciliable.setStartTime(System.currentTimeMillis());
                            Throwable e = reconciliable.getCompletable().blockingGet();
                            if (e == null) {
                                logger.debug("--- datacenter={} resourceVersion={} kind={} type={} pending={}ms execution={}ms",
                                        key.id(), reconciliable.getResourceVersion(),
                                        reconciliable.getKind(), reconciliable.getType(),
                                        reconciliable.getStartTime() - reconciliable.getSubmitTime(),
                                        System.currentTimeMillis() - reconciliable.getStartTime());
                            } else {
                                logger.warn("--- datacenter=" + key.id() + " reconciliable=" + reconciliable + " error:", e);
                            }
                        },
                        throwable -> logger.error("Error in work queue for datacenter=" + key.id() +":", throwable)
                );
        return queue;
    }

    /**
     * Free the resource associated with the cluster queue
     *
     * @param key
     */
    public void dispose(final ClusterKey key) {
        final Subject<Reconciliable> queue = queues.remove(key);
        if (queue != null) {
            queue.onComplete();
        }
    }
}