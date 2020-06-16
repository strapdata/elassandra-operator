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
import com.strapdata.strapkop.cache.StatefulsetCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.model.k8s.datacenter.RackStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trigger a DC reconciliation when STS event occurs
 */
@Handler
public class StatefulsetHandler extends TerminalHandler<K8sWatchEvent<V1StatefulSet>> {

    private final Logger logger = LoggerFactory.getLogger(StatefulsetHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    StatefulsetCache statefulsetCache;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    DataCenterStatusCache dataCenterStatusCache;

    @Inject
    MeterRegistry meterRegistry;

    AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "statefulset"));

    @PostConstruct
    public void initGauge() {
        managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    public void updateCache(V1StatefulSet sts) {
        Key key = new Key(sts.getMetadata().getLabels().get(OperatorLabels.PARENT), sts.getMetadata().getNamespace());
        statefulsetCache.compute(key, (k, v) -> {
            if (v == null)
                v = new TreeMap<>();
            String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);
            if (zone == null) {
                logger.warn("sts={}/{} has no {} label", sts.getMetadata().getName(), sts.getMetadata().getNamespace(), OperatorLabels.RACK);
            } else {
                logger.debug("sts={}/{} {}={}", sts.getMetadata().getName(), sts.getMetadata().getNamespace(), OperatorLabels.RACK, zone);
                v.put(zone, sts);
            }
            return v;
        });
    }
    /**
     * Update STS status cache and call dc controller if ready.
     * @param event
     * @throws Exception
     */
    @Override
    public void accept(K8sWatchEvent<V1StatefulSet> event) throws Exception {
        logger.debug("event type={} name={} generation={} resourceVersion={}",
                event.getType(), event.getResource().getMetadata().getName(),
                event.getResource().getMetadata().getGeneration(),
                event.getResource().getMetadata().getResourceVersion());
        logger.trace("event={}", event);
        switch(event.getType()) {
            case INITIAL: {
                updateCache(event.getResource());
                meterRegistry.counter("k8s.event.init", tags).increment();
                managed.incrementAndGet();
                reconcile(event.getResource());
            }
            break;

            case ADDED: {
                updateCache(event.getResource());
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed.incrementAndGet();
                reconcile(event.getResource());
            }
            break;

            case MODIFIED: {
                updateCache(event.getResource());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                reconcile(event.getResource());
            }
            break;

            case DELETED: {
                Key key = new Key(event.getResource().getMetadata().getName(), event.getResource().getMetadata().getNamespace());
                statefulsetCache.remove(key);
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
            }
            break;

            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                break;
        }
    }

    public void reconcile(V1StatefulSet sts) throws Exception {
        final String parent = sts.getMetadata().getLabels().get(OperatorLabels.PARENT);
        final String clusterName = sts.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
        final String namespace = sts.getMetadata().getNamespace();
        final Key key = new Key(parent, namespace);

        DataCenter dataCenter = dataCenterCache.get(key);
        if (dataCenter != null) {
            DataCenterStatus dataCenterStatus = dataCenterStatusCache.getOrDefault(key, dataCenter.getStatus());
            RackStatus rackStatus = dataCenterStatus.getRackStatuses().get(Integer.parseInt(sts.getMetadata().getLabels().get(OperatorLabels.RACKINDEX)));
            if (rackStatus == null || ObjectUtils.defaultIfNull(rackStatus.getReadyReplicas(), 0) != ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0)) {
                logger.info("datacenter={}/{} sts={} replicas={}/{}, triggering a dc statefulSetStatusUpdate",
                        parent, namespace,
                        sts.getMetadata().getName(),
                        sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas());
                Operation op = new Operation()
                        .withSubmitDate(new Date())
                        .withTriggeredBy("status update statefulset=" + sts.getMetadata().getName() + " replicas=" +
                                sts.getStatus().getReadyReplicas() + "/" + sts.getStatus().getReplicas());
                workQueues.submit(
                        new ClusterKey(clusterName, namespace),
                        sts.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.STATEFULSET, K8sWatchEvent.Type.MODIFIED,
                        dataCenterController.statefulsetStatusUpdate(dataCenter, op, sts)
                                .onErrorComplete(t -> {
                                    if (t instanceof NoSuchElementException) {
                                        return true;
                                    }
                                    logger.warn("datacenter={}/{} statefulSetUpdate failed: {}", parent, namespace, t.toString());
                                    return false;
                                }));
            }
        }
    }
}