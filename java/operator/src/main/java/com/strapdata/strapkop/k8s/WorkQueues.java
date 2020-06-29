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
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Infrastructure
public class WorkQueues {

    private static final Logger logger = LoggerFactory.getLogger(WorkQueues.class);

    /**
     * Keep the last submitted delayed reconciliation rather than all the queue...
     */
    private final Map<Key, Reconciliation> pendingReconciliations = new ConcurrentHashMap<>();
    private final Map<Key, Disposable> ongoingReconciliations = new ConcurrentHashMap<>();

    final MeterRegistry meterRegistry;
    final Scheduler scheduler;

    public WorkQueues(final MeterRegistry meterRegistry,
                      final ExecutorFactory executorFactory,
                      @Named("workqueue") UserExecutorConfiguration userExecutorConfiguration) {
        this.scheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
        this.meterRegistry = meterRegistry;
        meterRegistry.gaugeMapSize("reconciliation.pending", ImmutableList.of(), pendingReconciliations);
        meterRegistry.gaugeMapSize("reconciliation.ongoing", ImmutableList.of(), ongoingReconciliations);
    }

    public synchronized boolean submit(final Reconciliation reconciliation) {
        reconciliation.setSubmitTime(System.currentTimeMillis());
        Disposable ongoingReconciliation = ongoingReconciliations.get(reconciliation.getKey());
        if (ongoingReconciliation == null) {
            logger.debug("datacenter={} Immediate reconciliation={}", reconciliation.getKey().id(), reconciliation);
            ongoingReconciliations.put(reconciliation.getKey(), reconcile(reconciliation));
            return true;
        } else {
            logger.debug("datacenter={} Delaying reconciliation={}", reconciliation.getKey().id(), reconciliation);
            pendingReconciliations.put(reconciliation.getKey(), reconciliation);
            return false;
        }
    }

    public synchronized void reconcilied(Key key) {
        ongoingReconciliations.remove(key);
        Reconciliation delayedReconciliation = pendingReconciliations.remove(key);
        if (delayedReconciliation != null) {
            logger.debug("datacenter={} Start delayed reconciliation={}", delayedReconciliation.getKey().id(), delayedReconciliation);
            ongoingReconciliations.put(delayedReconciliation.getKey(), reconcile(delayedReconciliation));
        }
    }

    public void remove(Key key) {
        pendingReconciliations.remove(key);
        Disposable disposable = ongoingReconciliations.remove(key);
        if (disposable != null)
            disposable.dispose();
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
}