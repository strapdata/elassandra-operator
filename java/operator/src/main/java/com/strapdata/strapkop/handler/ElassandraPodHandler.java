package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.ElassandraPodDeletedReconcilier;
import com.strapdata.strapkop.reconcilier.ElassandraPodUnscheduledReconcilier;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodCondition;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

@Handler
public class ElassandraPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {
    private static final String POD_PENDING_PHASE = "Pending";

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodHandler.class);

    private final WorkQueues workQueues;
    private final ElassandraPodUnscheduledReconcilier elassandraPodUnscheduledReconcilier;
    private final ElassandraPodDeletedReconcilier elassandraPodDeletedReconcilier;

    public ElassandraPodHandler(WorkQueues workQueue, ElassandraPodUnscheduledReconcilier dataCenterUnscheduleReconcilier, ElassandraPodDeletedReconcilier dataCenterPodDeletedReconcilier) {
        this.workQueues = workQueue;
        this.elassandraPodUnscheduledReconcilier = dataCenterUnscheduleReconcilier;
        this.elassandraPodDeletedReconcilier = dataCenterPodDeletedReconcilier;
     }
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        logger.debug("Processing an ElassandraPod event={}", event);

        if (event.isUpdate()) {
            if (POD_PENDING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) { // TODO [ELE] add running phase with Error ???
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

                    ElassandraPod pod = ElassandraPod.fromV1Pod(event.getResource());
                    if (scheduleFailed.isPresent()) {
                        ClusterKey clusterKey = new ClusterKey(pod.getCluster(), pod.getNamespace());
                        workQueues.submit(
                                clusterKey,
                                elassandraPodUnscheduledReconcilier.reconcile(new Tuple2<>(new Key(pod.getParent(), pod.getNamespace()), pod)));
                    }
                }
            }
        } else if (event.isDeletion()) {
            ElassandraPod pod = ElassandraPod.fromV1Pod(event.getResource());
            ClusterKey clusterKey = new ClusterKey(pod.getCluster(), pod.getNamespace());
            workQueues.submit(
                    clusterKey,
                    elassandraPodDeletedReconcilier.reconcile(new Tuple2<>(new Key(pod.getParent(), pod.getNamespace()), pod)));
        }
    }

}