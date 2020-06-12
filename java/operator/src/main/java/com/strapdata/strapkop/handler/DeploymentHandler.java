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

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "deployment"));

    @PostConstruct
    public void initGauge() {
        meterRegistry.gauge("k8s.managed",  tags, managed);
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
                managed++;
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case ADDED:
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed++;
                break;

            case MODIFIED:
                meterRegistry.counter("k8s.event.modified", tags).increment();
                reconcileDeploymentIfAvailable(event.getResource());
                break;

            case DELETED:
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed--;
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