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

@Singleton
@Infrastructure
public class TaskQueues {

    private static final Logger logger = LoggerFactory.getLogger(TaskQueues.class);

    /**
     * Keep the pending tasks.
     */
    private final Map<Key, Disposable> ongoingTasks = new ConcurrentHashMap<>();
    private final Map<Key, Queue<Reconciliation>> pendingTasks = new ConcurrentHashMap<>();

    final MeterRegistry meterRegistry;
    final Scheduler scheduler;

    public TaskQueues(final MeterRegistry meterRegistry,
                      final ExecutorFactory executorFactory,
                      @Named("taskqueue") UserExecutorConfiguration userExecutorConfiguration) {
        this.scheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeMapSize("task.pending", ImmutableList.of(), pendingTasks);
        meterRegistry.gaugeMapSize("task.ongoing", ImmutableList.of(), ongoingTasks);
    }

    public synchronized boolean submit(final Reconciliation reconciliation) {
        reconciliation.setSubmitTime(System.currentTimeMillis());
        Disposable ongoingReconciliation = ongoingTasks.get(reconciliation.getKey());
        if (ongoingReconciliation == null) {
            logger.debug("datacenter={} Immediate task reconciliation={}", reconciliation.getKey().id(), reconciliation);
            ongoingTasks.put(reconciliation.getKey(), reconcile(reconciliation));
            return true;
        } else {
            Queue<Reconciliation> tasks = pendingTasks.computeIfAbsent(reconciliation.getKey(), k -> new ConcurrentLinkedQueue<>());
            tasks.add(reconciliation);
            logger.debug("datacenter={} Delaying task reconciliation={} queue.size={}",
                    reconciliation.getKey().id(), reconciliation, tasks.size());
            return false;
        }
    }

    synchronized void reconcilied(Key key) {
        ongoingTasks.remove(key);
        Queue<Reconciliation> tasks = pendingTasks.get(key);
        if (tasks != null) {
            Reconciliation task = tasks.poll();
            if (task != null) {
                logger.debug("datacenter={} Start delayed task reconciliation={}", task.getKey().id(), task);
                ongoingTasks.put(task.getKey(), reconcile(task));
            }
        }
    }

    Disposable reconcile(Reconciliation reconciliable) {
        reconciliable.setStartTime(System.currentTimeMillis());
        return reconciliable.getCompletable()
                .observeOn(scheduler)
                .doFinally(() -> reconcilied(reconciliable.getKey()))
                .subscribe(() -> {
                    logger.debug("key={} {}-{} generation/resourceVersion={}/{} pending={}ms execution={}ms",
                            reconciliable.getKey().id(),
                            reconciliable.getKind(), reconciliable.getType(),
                            reconciliable.getGeneration(), reconciliable.getResourceVersion(),
                            reconciliable.getStartTime() - reconciliable.getSubmitTime(),
                            System.currentTimeMillis() - reconciliable.getStartTime());
                }, t -> {
                    logger.warn("key=" + reconciliable.getKey().id() + " reconciliable=" + reconciliable + " error:", t);
                });
    }

    public void remove(Key key) {
        pendingTasks.remove(key);
        Disposable disposable = ongoingTasks.remove(key);
        if (disposable != null)
            disposable.dispose();
    }

}