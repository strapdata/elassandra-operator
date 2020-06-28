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

package com.strapdata.strapkop.k8s;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.reconcilier.Reconciliation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Singleton
@Infrastructure
public class WorkQueues {

    private static final Logger logger = LoggerFactory.getLogger(WorkQueues.class);

    private final Map<Key, Queue<Reconciliation>> pendingQueues = new ConcurrentHashMap<>();
    private final Map<Key, Disposable> disposableMap = new ConcurrentHashMap<>();
    private final Map<Key, AtomicBoolean> processingMap = new ConcurrentHashMap<>();

    final MeterRegistry meterRegistry;
    final Scheduler scheduler;

    public WorkQueues(final MeterRegistry meterRegistry,
                      final ExecutorFactory executorFactory,
                      @Named("workqueue") UserExecutorConfiguration userExecutorConfiguration) {
        this.scheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeMapSize("workqueues.processing", ImmutableList.of(), disposableMap);
        meterRegistry.gaugeMapSize("workqueues.total", ImmutableList.of(), processingMap);
    }

    public synchronized boolean submit(final Reconciliation reconciliation) {
        reconciliation.setSubmitTime(System.currentTimeMillis());
        AtomicBoolean processing = processingMap.compute(reconciliation.getKey(), (k,v) -> {
            if (v == null)
                v = new AtomicBoolean(false);
            return v;
        });
        if (processing.compareAndSet(false, true)) {
            reconciliation.setStartTime(System.currentTimeMillis());
            logger.debug("datacenter={} Immediate reconciliation={}", reconciliation.getKey().id(), reconciliation);
            disposableMap.put(reconciliation.getKey(), reconcile(reconciliation));
            return true;
        } else {
            logger.debug("datacenter={} Delaying reconciliation={}", reconciliation.getKey().id(), reconciliation);
            pendingQueues.compute(reconciliation.getKey(), (k, v) -> {
                if (v == null)
                    v = new ConcurrentLinkedQueue<>();
                v.add(reconciliation);
                return v;
            });
            return false;
        }
    }

    public synchronized void reconcilied(Key key) {
        Queue<Reconciliation> pendingQueue = pendingQueues.get(key);
        if (pendingQueue != null && !pendingQueue.isEmpty()) {
            Reconciliation reconciliation = pendingQueue.poll();
            reconciliation.setStartTime(System.currentTimeMillis());
            logger.debug("datacenter={} Start pending reconciliation={}", reconciliation.getKey().id(), reconciliation);
            reconcile(reconciliation);
        } else {
            AtomicBoolean processing = processingMap.get(key);
            if (processing != null)
                processing.set(false);
        }
    }

    public void remove(Key key) {
        processingMap.remove(key);
        pendingQueues.remove(key);
        Disposable disposable = disposableMap.remove(key);
        if (disposable != null)
            disposable.dispose();
    }

    Disposable reconcile(Reconciliation reconciliable) {
        reconciliable.setStartTime(System.currentTimeMillis());
        return reconciliable.getCompletable()
                .observeOn(scheduler)
                .doFinally(() -> reconcilied(reconciliable.getKey()))
                .subscribe(() -> {
                    Queue pendingQueue = pendingQueues.get(reconciliable.getKey());
                    logger.debug("key={} {}-{} generation/resourceVersion={}/{} pending={}ms execution={}ms pendingQueue.size={}",
                            reconciliable.getKey().id(),
                            reconciliable.getKind(), reconciliable.getType(),
                            reconciliable.getGeneration(), reconciliable.getResourceVersion(),
                            reconciliable.getStartTime() - reconciliable.getSubmitTime(),
                            System.currentTimeMillis() - reconciliable.getStartTime(),
                            pendingQueue == null ? null : pendingQueue.size()
                            );
                }, t -> {
                    logger.warn("key=" + reconciliable.getKey().id() + " reconciliable=" + reconciliable + " error:", t);
                });
    }
}