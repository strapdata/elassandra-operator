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
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.kubernetes.client.openapi.models.V1Deployment;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Notify datacenter controller when a deployment is ready (for plugins)
 */
@Handler
public class DeploymentHandler extends TerminalHandler<K8sWatchEvent<V1Deployment>> {

    private final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    MeterRegistry meterRegistry;

    AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "deployment"));

    @PostConstruct
    public void initGauge() {
        managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    @Override
    public void accept(K8sWatchEvent<V1Deployment> event) throws Exception {
        logger.debug("event type={} name={} generation={} resourceVersion={}",
                event.getType(), event.getResource().getMetadata().getName(),
                event.getResource().getMetadata().getGeneration(),
                event.getResource().getMetadata().getResourceVersion());
        logger.trace("Deployment event={}", event);
        switch(event.getType()) {
            case INITIAL:
                meterRegistry.counter("k8s.event.init", tags).increment();
                managed.incrementAndGet();
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case ADDED:
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed.incrementAndGet();
                break;

            case MODIFIED:
                meterRegistry.counter("k8s.event.modified", tags).increment();
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case DELETED:
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
                break;

            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("V1Deployment error");
        }
    }

    public static boolean isDeploymentAvailable(V1Deployment dep) {
        return ObjectUtils.defaultIfNull(dep.getStatus().getAvailableReplicas(), 0) > 0;
    }

    // trigger a dc reconciliation when plugin deployments become available (allow reaper to register)
    public void reconcileDeploymentIfAvailable(V1Deployment deployment) throws Exception {
        if (isDeploymentAvailable(deployment)) {
            final String parent = deployment.getMetadata().getLabels().get(OperatorLabels.PARENT);
            final String clusterName = deployment.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
            final String namespace = deployment.getMetadata().getNamespace();
            final Key key = new Key(parent, namespace);
            DataCenter dataCenter = dataCenterCache.get(key);
            if (dataCenter != null) {
                logger.info("datacenter={}/{} deployment={}/{} is available, triggering a dc deploymentAvailable",
                        parent, namespace, deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                Operation op = new Operation()
                        .withSubmitDate(new Date())
                        .withTriggeredBy("status update deployment=" + deployment.getMetadata().getName());
                workQueues.submit(
                        new ClusterKey(clusterName, deployment.getMetadata().getNamespace()),
                        deployment.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER, K8sWatchEvent.Type.MODIFIED,
                        dataCenterController.deploymentAvailable(dataCenter, op, deployment));
            }
        }
    }
}