package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.ReaperPod;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUnscheduledReconcilier;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodCondition;
import io.kubernetes.client.models.V1PodStatus;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class ElassandraPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {
    private static final String POD_PENDING_PHASE = "Pending";

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodHandler.class);

    private final WorkQueue workQueue;
    private final DataCenterUnscheduledReconcilier dataCenterReconcilier;

    public ElassandraPodHandler(WorkQueue workQueue, DataCenterUnscheduledReconcilier dataCenterReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterReconcilier = dataCenterReconcilier;
     }
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        if (event.isUpdate()) {
            logger.debug("Processing a ElassandraPod event={}", event);
            if (POD_PENDING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) { // TODO [ELE] add running phase with Error ???
                if (event.getResource().getStatus() != null && event.getResource().getStatus().getConditions() != null) {
                    List<V1PodCondition> conditions = event.getResource().getStatus().getConditions();
                    Optional<V1PodCondition> scheduleFailed = conditions.stream()
                            .filter((condition) -> ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus()) || "Unschedulable".equals(condition.getType())))
                            .findFirst();

                    ElassandraPod pod = ElassandraPod.fromV1Pod(event.getResource());
                    if (scheduleFailed.isPresent()) {
                        ClusterKey clusterKey = new ClusterKey(pod.getCluster(), pod.getNamespace());
                        workQueue.submit(
                                clusterKey,
                                dataCenterReconcilier.reconcile(new Tuple2<>(new Key(pod.getParent(), pod.getNamespace()), pod)));
                    }
                }
            }
        } else {
            logger.trace("Ignore ReaperPod event={}", event);
        }
    }

}