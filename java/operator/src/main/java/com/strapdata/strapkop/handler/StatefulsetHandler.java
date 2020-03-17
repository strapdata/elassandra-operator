package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.kubernetes.client.models.V1StatefulSet;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Objects;

@Handler
public class StatefulsetHandler extends TerminalHandler<K8sWatchEvent<V1StatefulSet>> {

    private final Logger logger = LoggerFactory.getLogger(StatefulsetHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    DataCenterCache dataCenterCache;

    /**
     * Update STS status cache and call dc controller if ready.
     * @param event
     * @throws Exception
     */
    @Override
    public void accept(K8sWatchEvent<V1StatefulSet> event) throws Exception {
        final V1StatefulSet sts = event.getResource();
        logger.debug("StatefulSet event type={} sts={}/{} status={}",
                event.getType(), sts.getMetadata().getName(), sts.getMetadata().getNamespace(), sts.getStatus());

        Key key = new Key(sts.getMetadata().getName(), sts.getMetadata().getNamespace());
        switch(event.getType()) {
            case INITIAL:
            case ADDED:
            case MODIFIED:
                if (isStafulSetReady(sts)) {
                    final String clusterName = sts.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
                    DataCenter dataCenter = dataCenterCache.get(new Key(sts.getMetadata().getLabels().get(OperatorLabels.PARENT), sts.getMetadata().getNamespace()));
                    logger.info("datacenter={} sts={}/{} is ready, triggering a dc statefulSetUpdate",
                            dataCenter.id(), sts.getMetadata().getName(), sts.getMetadata().getNamespace());
                    workQueues.submit(
                            new ClusterKey(clusterName, sts.getMetadata().getNamespace()),
                            dataCenterController.statefulsetUpdate(dataCenter, sts)
                                    .doOnError(t -> {
                                        logger.warn("datcenter={} statefulSetUpdate failed: {}", dataCenter.id(), t.toString());
                                    }));
                }
                break;
            case ERROR:
                throw new IllegalStateException("Statefulset error");
            case DELETED:
        }
    }

    public static boolean isStafulSetReady(V1StatefulSet sts) {
        return Objects.equals(sts.getSpec().getReplicas(), ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0)) &&
                Objects.equals(sts.getStatus().getCurrentRevision(), sts.getStatus().getUpdateRevision());
    }
}