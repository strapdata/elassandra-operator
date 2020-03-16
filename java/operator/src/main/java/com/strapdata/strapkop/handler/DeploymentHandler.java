package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.kubernetes.client.models.V1Deployment;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Objects;

/**
 * Notify datacenter controller when a deployment is ready (for plugins)
 */
@Handler
public class DeploymentHandler extends TerminalHandler<K8sWatchEvent<V1Deployment>> {

    private final Logger logger = LoggerFactory.getLogger(DeploymentHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    DataCenterController dataCenterController;

    @Override
    public void accept(K8sWatchEvent<V1Deployment> event) throws Exception {
        final V1Deployment deployment = event.getResource();
        logger.debug("Deployment event type={} deployment={}/{} status={}",
                event.getType(), deployment.getMetadata().getName(), deployment.getMetadata().getNamespace(), deployment.getStatus());

        switch(event.getType()) {
            case INITIAL:
            case ADDED:
            case MODIFIED:
                if (isDeploymentAvailable(deployment)) {
                    final String clusterName = deployment.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
                    DataCenter dataCenter = dataCenterCache.get(new Key(deployment.getMetadata().getLabels().get(OperatorLabels.PARENT), deployment.getMetadata().getNamespace()));
                    logger.info("datacenter={} deployment={}/{} is available, triggering a dc deploymentAvailable",
                            dataCenter.id(),deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                    workQueues.submit(
                            new ClusterKey(clusterName, deployment.getMetadata().getNamespace()),
                            dataCenterController.deploymentAvailable(dataCenter, deployment));
                }
                break;
            case ERROR:
                throw new IllegalStateException("V1Deployment error");
            case DELETED:
        }
    }

    public static boolean isDeploymentReady(V1Deployment dep) {
        return Objects.equals(dep.getSpec().getReplicas(), ObjectUtils.defaultIfNull(dep.getStatus().getReadyReplicas(), 0));
    }

    public static boolean isDeploymentAvailable(V1Deployment dep) {
        return ObjectUtils.defaultIfNull(dep.getStatus().getAvailableReplicas(), 0) > 0;
    }
}