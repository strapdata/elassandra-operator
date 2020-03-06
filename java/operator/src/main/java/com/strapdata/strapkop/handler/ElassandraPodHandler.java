package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.ElassandraPodDeletedReconcilier;
import com.strapdata.strapkop.reconcilier.ElassandraPodUnscheduledReconcilier;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodCondition;
import io.micrometer.core.instrument.MeterRegistry;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Track Elassandra POD status to update the ElassandraNodeStatusCache that trigger Cassandra status pooling.
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

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodHandler.class);

    private final WorkQueues workQueues;
    private final ElassandraPodUnscheduledReconcilier elassandraPodUnscheduledReconcilier;
    private final ElassandraPodDeletedReconcilier elassandraPodDeletedReconcilier;
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final MeterRegistry meterRegistry;

    private Integer elassandraNodeStatusCacheMaxSize = 0;

    public ElassandraPodHandler(final WorkQueues workQueue,
                                final ElassandraPodUnscheduledReconcilier dataCenterUnscheduleReconcilier,
                                final ElassandraPodDeletedReconcilier dataCenterPodDeletedReconcilier,
                                final ElassandraNodeStatusCache elassandraNodeStatusCache,
                                final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.elassandraPodUnscheduledReconcilier = dataCenterUnscheduleReconcilier;
        this.elassandraPodDeletedReconcilier = dataCenterPodDeletedReconcilier;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.meterRegistry = meterRegistry;
     }
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        ElassandraPod pod = ElassandraPod.fromV1Pod(event.getResource());
        ClusterKey clusterKey = new ClusterKey(pod.getCluster(), pod.getNamespace());

        logger.debug("ElassandraPod event type={} pod={}", event.getType(), pod.id());

        if (event.isUpdate()) {
            if (POD_RUNNING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) {
                // when Elassandra pod start, put ElassandraNodeStatus in the cache to start status pooling.
                ElassandraNodeStatus oldStatus = elassandraNodeStatusCache.putIfAbsent(pod, ElassandraNodeStatus.UNKNOWN);
                logger.debug("Running pod={} ElassandraNodeStatus was={}", pod.id(), oldStatus);
                if (oldStatus == null) {
                    this.elassandraNodeStatusCacheMaxSize = Math.max(elassandraNodeStatusCacheMaxSize, this.elassandraNodeStatusCache.size());
                    this.meterRegistry.gauge("node_status_cache.max", this.elassandraNodeStatusCacheMaxSize);
                }
            } else if (POD_PENDING_PHASE.equalsIgnoreCase(event.getResource().getStatus().getPhase())) { // TODO [ELE] add running phase with Error ???
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

                    logger.debug("Pending pod={} conditions={}", pod.id(), conditions);
                    if (elassandraNodeStatusCache.remove(pod, ElassandraNodeStatus.UNKNOWN)) {
                        logger.debug("Pending pod={} => ElassandraNodeStatus removed from cache", pod.id());
                    };
                    if (scheduleFailed.isPresent()) {
                        workQueues.submit(
                                clusterKey,
                                elassandraPodUnscheduledReconcilier.reconcile(new Tuple2<>(new Key(pod.getParent(), pod.getNamespace()), pod)));
                    }
                }
            }
        } else if (event.isDeletion()) {
            if (elassandraNodeStatusCache.remove(pod, ElassandraNodeStatus.UNKNOWN)) {
                logger.debug("Deleted pod={} => ElassandraNodeStatus removed from cache", pod.id());
            };
            workQueues.submit(
                    clusterKey,
                    elassandraPodDeletedReconcilier.reconcile(new Tuple2<>(new Key(pod.getParent(), pod.getNamespace()), pod)));
        }

        this.meterRegistry.gauge("node_status_cache.current", this.elassandraNodeStatusCache.size());
    }

}