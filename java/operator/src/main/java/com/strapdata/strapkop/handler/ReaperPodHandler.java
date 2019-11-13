package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.ReaperPod;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.models.V1Pod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class ReaperPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {

    private final Logger logger = LoggerFactory.getLogger(ReaperPodHandler.class);

    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);

    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterReconcilier;

    public ReaperPodHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
     }
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        logger.debug("Processing a ReaperPod event={}", event);

        if (event.getType().equals(MODIFIED)) {
            // currently for reaper watch only MODIFIED status to try a cluster registration
            ReaperPod pod = new ReaperPod(event.getResource());

            if (pod.isReady()) {
                ClusterKey clusterKey = new ClusterKey(pod.getClusterName(), pod.getNamespace());
                workQueue.submit(
                        clusterKey,
                        dataCenterReconcilier.reconcile((new Key(pod.getElassandraDatacenter(), pod.getNamespace()))));
            }
        } else {
            logger.trace("Ignore ReaperPod event={}", event);
        }
    }

}
