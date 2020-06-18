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
import com.strapdata.strapkop.cache.PodCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.Pod;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Track Elassandra POD status to update the ElassandraNodeStatusCache that trigger Cassandra status pooling.
 * When POD is ready => Add ElassandraNodeStatus in status UNKNOWN in the cache to pool Cassandra status
 * When POD is deleted => trigger the ElassandraPodDeletedReconcilier to manage PVC lifecycle
 * When POD is pending => trigger the ElassandraPodUnscheduledReconcilier to manage eventual rollback.
 */
@Handler
public class ElassandraPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {

    // See https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/#pod-phase
    private static final String POD_PENDING_PHASE = "Pending";
    private static final String POD_RUNNING_PHASE = "Running";
    private static final String POD_SUCCESSDED_PHASE = "Succeeded";
    private static final String POD_FAILED_PHASE = "Failed";
    private static final String POD_UNKNOW_PHASE = "Unknown";

    public static final String CONTAINER_NAME = "elassandra";

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    PodCache podCache;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    MeterRegistry meterRegistry;

    AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "pod"));

    @PostConstruct
    public void initGauge() {
        managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    public void updateCache(V1Pod pod) {
        podCache.put(new Key(pod.getMetadata()), pod);
    }

    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        logger.trace("ElassandraPod event={}", event);
        switch(event.getType()) {
            case INITIAL:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.init", tags).increment();
                managed.incrementAndGet();
                updateCache(event.getResource());
                break;

            case ADDED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed.incrementAndGet();
                updateCache(event.getResource());
                break;

            case MODIFIED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                updateCache(event.getResource());
                if (POD_PENDING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) {
                    if (event.getResource().getStatus() != null && event.getResource().getStatus().getConditions() != null) {
                        List<V1PodCondition> conditions = event.getResource().getStatus().getConditions();
                        Optional<V1PodCondition> scheduleFailed = conditions.stream()
                                .filter((condition) ->
                                        // Pod failed, so restart will probably go to an endless restart loop
                                        ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus()) ||
                                                // pod not scheduled
                                                "Unschedulable".equals(condition.getType()))
                                )
                                .findFirst();

                        V1Pod pod = event.getResource();
                        String parent = Pod.extractLabel(pod, OperatorLabels.PARENT);
                        String clusterName = Pod.extractLabel(pod, OperatorLabels.CLUSTER);
                        String datacenterName = Pod.extractLabel(pod, OperatorLabels.DATACENTER);
                        String podName = pod.getMetadata().getName();
                        String namespace = pod.getMetadata().getNamespace();

                        ClusterKey clusterKey = new ClusterKey(clusterName, datacenterName);
                        logger.debug("Pending pod={}/{} conditions={}", podName, namespace, conditions);
                        if (scheduleFailed.isPresent()) {
                            meterRegistry.counter("k8s.pod.unschedulable", tags).increment();
                            workQueues.submit(
                                    clusterKey,
                                    pod.getMetadata().getResourceVersion(),
                                    Reconciliable.Kind.ELASSANDRA_POD, K8sWatchEvent.Type.MODIFIED,
                                    dataCenterController.unschedulablePod(new Pod(pod, CONTAINER_NAME)));
                        }
                    }
                }
                break;

            case DELETED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                V1Pod pod = event.getResource();
                podCache.remove(new Key(pod.getMetadata()));
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
                break;

            case ERROR:
                logger.warn("event type={}", event.getType());
                meterRegistry.counter("k8s.event.error", tags).increment();
                break;
        }
    }
}