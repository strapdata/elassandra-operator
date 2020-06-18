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

package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueues workQueues;
    private final DataCenterController dataCenterController;
    private final MeterRegistry meterRegistry;
    private final DataCenterCache dataCenterCache;
    private final DataCenterStatusCache dataCenterStatusCache;

    final AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "datacenter"));

    public DataCenterHandler(final WorkQueues workQueue,
                             final DataCenterController dataCenterController,
                             final DataCenterCache dataCenterCache,
                             final DataCenterStatusCache dataCenterStatusCache,
                             final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterStatusCache = dataCenterStatusCache;
        this.meterRegistry = meterRegistry;
        this.managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    void updateCaches(DataCenter dataCenter) {
        Key key = new Key(dataCenter.getMetadata());
        dataCenterCache.put(key, dataCenter);
        dataCenter.setStatus(dataCenterStatusCache.compute(key, (k,v) -> {
            if (v == null)
                v = dataCenter.getStatus();
            return v;
        })); // overwrite the watched dc status by the one we have updated more recently.
    }

    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        logger.debug("event type={} name={} generation={} resourceVersion={}",
                event.getType(), event.getResource().getMetadata().getName(),
                event.getResource().getMetadata().getGeneration(),
                event.getResource().getMetadata().getResourceVersion());
        logger.trace("event={}", event);

        DataCenter dataCenter;
        switch (event.getType()) {
            case INITIAL:
                // trigger a dc reconcile on operator start.
                dataCenter = event.getResource();
                updateCaches(dataCenter);
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER, event.getType(),
                        dataCenterController.initDatacenter(dataCenter, new Operation()
                                .withSubmitDate(new Date())
                                .withTriggeredBy("Datacenter init"))
                                .doOnComplete(() -> managed.incrementAndGet())
                                .doFinally(() -> meterRegistry.counter("k8s.event.init", tags).increment()));
                break;

            case ADDED: {
                dataCenter = event.getResource();
                updateCaches(dataCenter);
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER,
                        event.getType(),
                        dataCenterController.initDatacenter(dataCenter, new Operation()
                                .withSubmitDate(new Date())
                                .withTriggeredBy("Datacenter added"))
                                .doOnComplete(() -> managed.incrementAndGet())
                                .doFinally(() -> meterRegistry.counter("k8s.event.added", tags).increment())
                );
            }
            break;

            case MODIFIED: {
                dataCenter = event.getResource();
                // read dc status from cache rather than from event because last write may be not yet available.
                updateCaches(dataCenter);
                Long observedGeneration = dataCenter.getStatus().getObservedGeneration();
                if (observedGeneration == null || dataCenter.getMetadata().getGeneration() > observedGeneration) {
                    logger.debug("dataCenter={} spec new generation={}", dataCenter.id(), dataCenter.getMetadata().getGeneration());
                    workQueues.submit(new ClusterKey(event.getResource()),
                            dataCenter.getMetadata().getResourceVersion(),
                            Reconciliable.Kind.DATACENTER, event.getType(),
                            dataCenterController.updateDatacenter(
                                    dataCenter,
                                    new Operation()
                                            .withSubmitDate(new Date())
                                            .withTriggeredBy("Datacenter modified spec generation=" + dataCenter.getMetadata().getGeneration()))
                                    .doFinally(() -> meterRegistry.counter("k8s.event.modified", tags).increment())
                    );
                }
            }
            break;

            case DELETED: {
                dataCenter = event.getResource();
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER, event.getType(),
                        dataCenterController.deleteDatacenter(dataCenter)
                                .doOnComplete(() -> {
                                    managed.decrementAndGet();
                                    final Key key = new Key(event.getResource().getMetadata());
                                    workQueues.dispose(new ClusterKey(event.getResource()));
                                })
                                .doFinally(() -> meterRegistry.counter("k8s.event.deleted", tags).increment())
                );
            }
            break;

            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("Datacenter error event");

            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
    }
}
