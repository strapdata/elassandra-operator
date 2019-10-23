package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.cassandra.RackMode;
import com.strapdata.model.k8s.cassandra.RackStatus;
import com.strapdata.model.k8s.task.CleanupTaskSpec;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1StatefulSet;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Flowable;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * This class implement the complex logic of updating, scaling up or down statefulsets with multiple rack, one by one.
 */
@Prototype
class StatefulSetUpdateAction {
    
    private final Logger logger = LoggerFactory.getLogger(StatefulSetUpdateAction.class);

    private static Set<ElassandraNodeStatus> MOVING_ELASSANDRA_POD_STATUSES = ImmutableSet.of(
            ElassandraNodeStatus.JOINING, ElassandraNodeStatus.DRAINING, ElassandraNodeStatus.LEAVING, ElassandraNodeStatus.MOVING, ElassandraNodeStatus.STARTING);

    private final AppsV1Api appsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;

    public StatefulSetUpdateAction(AppsV1Api appsApi,
                                   K8sResourceUtils k8sResourceUtils,
                                   SidecarClientFactory sidecarClientFactory,
                                   ElassandraNodeStatusCache elassandraNodeStatusCache) {
        this.appsApi = appsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
    }
    
    /**
     * This is the main function.
     *
     * Given a map of existing racks, and a map of expected rack... do something to decrease the gap without elassandra downtime.
     *
     * Only one expected rack should have a different replicas value than the existing one.
     */
    void updateNextStatefulSet(DataCenter dataCenter,
                               TreeMap<String, V1StatefulSet> existingStsMap,
                               TreeMap<String, V1StatefulSet> newtStsMap) throws ApiException, MalformedURLException, StrapkopException {

        Map<ElassandraNodeStatus, List<String>> podsByStatus = getStatusesFromCache(dataCenter, existingStsMap);
        Map<Boolean, List<String>> rackByReadiness = fetchReadinesses(existingStsMap);
        Map<RackMode, TreeSet<String>> racksByRackMode = fetchRackModes(dataCenter, existingStsMap, newtStsMap);

        // update dc status with what we observed
        updateDatacenterStatus(dataCenter, existingStsMap, newtStsMap, podsByStatus, rackByReadiness, racksByRackMode);

        // "garde-fou" 1
        if (racksByRackMode.get(RackMode.SCALE_UP).size() > 0 && racksByRackMode.get(RackMode.SCALE_DOWN).size() > 0) {
            // here we have some racks to scale-up and some racks to scale-down... it should not happens
            dataCenter.getStatus().setPhase(DataCenterPhase.ERROR);
            logger.warn("inconsistent state, racks [{}] must scale up while racks [{}] must scale down",
                    racksByRackMode.get(RackMode.SCALE_UP), racksByRackMode.get(RackMode.SCALE_DOWN));
        }
    
        // "garde-fou" 2
        if (racksByRackMode.get(RackMode.SCALE_UP).size() > 1 || racksByRackMode.get(RackMode.SCALE_DOWN).size() > 1) {

            // the scaling of which rack should be decided has been moved upstream. They should never be multiple rack that need to
            // be scaled at this point of the reconciliation.
            
            dataCenter.getStatus().setPhase(DataCenterPhase.ERROR);
            logger.warn("inconsistent state, multiple racks request scaling dc={}", dataCenter.getMetadata().getName());
        }
    
        // ensure all statefulsets are ready
        /*
        if (rackByReadiness.get(false).size() > 0) {
            logger.warn("some statefulsets are not ready, skipping sts replacement : {}", rackByReadiness.get(false));
            
            // TODO: check if some pods are stuck with old configuration and restart it manually
            //        see https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/#forced-rollback
            
            return ;
        }
        */
        
        // ensure all nodes are joined or decommissioned
        if (MOVING_ELASSANDRA_POD_STATUSES.stream().anyMatch(status -> podsByStatus.get(status).size() > 0)) {
            logger.warn("some pods are in a moving operational status, delaying sts replacement");
            return;
        }
        
        // TODO: check that's if there is decommissioned node but no scale-down operation, we should still delete the node (in case user has trigger scale down then up to fast)
    
        // first, we update a rack that does not need to scale
        if (racksByRackMode.get(RackMode.BEHIND).size() > 0) {
            String rack = racksByRackMode.get(RackMode.BEHIND).first();
            updateRack(dataCenter, newtStsMap.get(rack));
        }
        // when all racks are up-to-date except the one we need to scale-up, we trigger the scale+1 as well as rack update in one shot
        else if (racksByRackMode.get(RackMode.SCALE_UP).size() > 0) {
            scaleUp(dataCenter, racksByRackMode.get(RackMode.SCALE_UP).first(), existingStsMap, newtStsMap);
        }
        // ...scale down is almost the same except that we start by decommissioning the node
        else if (racksByRackMode.get(RackMode.SCALE_DOWN).size() > 0) {
            scaleDown(dataCenter, racksByRackMode.get(RackMode.SCALE_DOWN).first(), existingStsMap, newtStsMap, podsByStatus);
        }
        // otherwise all racks are OK, we might need to trigger a cleanup
        else {
            
            // if we go from scaling to running phase, we trigger a cleanup
            if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.SCALING_DOWN) ||
                    (dataCenter.getStatus().getPhase().equals(DataCenterPhase.SCALING_UP) && dataCenter.getSpec().getReplicas() > 1)) {
                logger.info("Scaling of dc={} terminated, triggering a dc cleanup", dataCenter.getMetadata().getName());
                triggerCleanupTask(dataCenter);
            }

            dataCenter.getStatus().setPhase(DataCenterPhase.RUNNING);
            logger.debug("Everything is fine, nothing to do");
        }
    }
    
    /**
     * Based on metadata.generation of DC, check if sts need an update
     */
    private boolean stsNeedsUpdate(final V1StatefulSet existingSts, final DataCenter dc) {
        final Long stsGen = Long.valueOf(existingSts.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION));
        final Long dcGen = dc.getMetadata().getGeneration();
        
        // TODO: take care of user defined config map changes
        
        if (dcGen == null) {
            return false;
        }
        
        return stsGen < dcGen;
    }
    
    /**
     * Build a map of rack -> RackMode.
     * The RackMode describes if the rack is up-to-date or if it needs scale-up, down, or rolling update.
     */
    private Map<RackMode, TreeSet<String>> fetchRackModes(DataCenter dataCenter, TreeMap<String, V1StatefulSet> existingStsMap, TreeMap<String, V1StatefulSet> newtStsMap) {
        
        Map<RackMode, TreeSet<String>> modes = new HashMap<>();
        for (RackMode mode : RackMode.values()) {
            modes.put(mode, new TreeSet<>());
        }
        
        // some updateNextStatefulSet modes are cumulative (UP or DOWN imply UPDATE)
        // some others are exclusive (UP and DOWN, or UPDATE and NOTHING)
        newtStsMap.forEach((rack, sts) -> {
            
            if (sts.getSpec().getReplicas() > existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(RackMode.SCALE_UP).add(rack);
            } else if (sts.getSpec().getReplicas() < existingStsMap.get(rack).getSpec().getReplicas()) {
                modes.get(RackMode.SCALE_DOWN).add(rack);
            }
            // compare the datacenter generation and the sts annotation to see if we need an update
            else if (stsNeedsUpdate(existingStsMap.get(rack), dataCenter)) {
                modes.get(RackMode.BEHIND).add(rack);
                logger.debug("sts {} has to be updated\nold:{}\nnew:{}", sts.getMetadata().getName(), existingStsMap.get(rack), sts);
            }
            else {
                modes.get(RackMode.NORMAL).add(rack);
            }
        });
        
        return modes;
    }
    
    /**
     * Enumerate the list of pods name based on existing statefulsets and .spec.replicas
     * This does not execute any network operation
     */
    private List<String> enumeratePods(DataCenter dataCenter, TreeMap<String, V1StatefulSet> existingStsMap) {
        
        List<String> pods = new ArrayList<>();
        
        for (Map.Entry<String, V1StatefulSet> entry : existingStsMap.entrySet()) {
            String rack = entry.getKey();
            V1StatefulSet sts = entry.getValue();
            for (int i = 0; i < sts.getSpec().getReplicas(); i++) {
                pods.add(OperatorNames.podName(dataCenter, rack, i));
            }
        }
        
        return pods;
    }
    
    /**
     * Build a map of rack readiness by inspecting statefulset status
     */
    private Map<Boolean, List<String>> fetchReadinesses(TreeMap<String, V1StatefulSet> existingStsMap) {
        Map<Boolean, List<String>> readinesses = new HashMap<>();
        readinesses.put(true, new ArrayList<>());
        readinesses.put(false, new ArrayList<>());
        
        for (V1StatefulSet sts : existingStsMap.values()) {
            final Boolean readiness =
                    sts.getStatus() != null &&
                            Objects.equals(sts.getStatus().getReplicas(), sts.getSpec().getReplicas()) &&
                            Objects.equals(Optional.ofNullable(sts.getStatus().getReadyReplicas()).orElse(0), sts.getSpec().getReplicas()) &&
                            Objects.equals(Optional.ofNullable(sts.getStatus().getCurrentReplicas()).orElse(0), sts.getSpec().getReplicas()) &&
                            (
                                    // no rolling update in progress
                                    Strings.isNullOrEmpty(sts.getStatus().getUpdateRevision()) ||
                                            Objects.equals(sts.getStatus().getUpdateRevision(), sts.getStatus().getCurrentRevision())
                            );
            readinesses.get(readiness).add(sts.getMetadata().getLabels().get(OperatorLabels.RACK));
        }
        
        return readinesses;
    }
    
    /**
     * Retrieve elassandra pod statuses (cassandra operation mode) from the cache
     */
    private Map<ElassandraNodeStatus, List<String>> getStatusesFromCache(DataCenter dataCenter, TreeMap<String, V1StatefulSet> existingStsMap) {
        
        Map<ElassandraNodeStatus, List<String>> statuses = new HashMap<>();
        for (ElassandraNodeStatus status : ElassandraNodeStatus.values()) {
            statuses.put(status, new ArrayList<>());
        }
        
        for (String podName : enumeratePods(dataCenter, existingStsMap)) {
            ElassandraNodeStatus nodeStatus = Optional
                    .ofNullable(elassandraNodeStatusCache.get(new ElassandraPod(dataCenter, podName)))
                    .orElse(ElassandraNodeStatus.UNKNOWN);
            statuses.get(nodeStatus).add(podName);
        }
        
        return statuses;
    }
    
    /**
     * Create an ElassandraTask of type cleanup
     */
    private void triggerCleanupTask(DataCenter dataCenter) throws ApiException {
        k8sResourceUtils.createTask(dataCenter, "cleanup", spec -> spec.setCleanup(new CleanupTaskSpec()));
    }
    
    // TODO: reuse part of this commented code to delete failed stuck pod with old config
//    private void tryRecoverUnschedulablePods() throws ApiException {
//
//        // search for racks that are out-of-date (RackMode.BEHIND) and that contains unschedulable pods.
//        // If found, update the first rack and kill the unschedulable pods (sts won't recreate those pods automatically sometimes...)
//
//        Optional<Map.Entry<String, List<V1Pod>>> unschedulableRack = fetchUnschedulablePods()
//                .entrySet()
//                .stream()
//                .filter(e -> racksByRackMode.get(RackMode.BEHIND).contains(e.getKey()))
//                .findFirst();
//
//        if (unschedulableRack.isPresent()) {
//            logger.info("recovering unschedulable pods {} in rack {}",
//                    unschedulableRack.get().getValue().stream().map(pod -> pod.getMetadata().getName()).collect(Collectors.toList()),
//                    unschedulableRack.get().getKey());
//            updateRack(unschedulableRack.get().getKey());
//            for (V1Pod pod : unschedulableRack.get().getValue()) {
//                try {
//                    coreApi.deleteNamespacedPod(pod.getMetadata().getName(), pod.getMetadata().getNamespace(), new V1DeleteOptions().gracePeriodSeconds(0L), null, null, null, null, null);
//                }
//                catch (com.google.gson.JsonSyntaxException e) {
//                    logger.debug("safely ignoring exception (see https://github.com/kubernetes-client/java/issues/86)", e);
//                }
//            }
//        }
//    }
//
//    private TreeMap<String, List<V1Pod>> fetchUnschedulablePods() {
//        return podsByPhase.getConnection(PodPhase.PENDING).stream()
//                .filter(pod ->
//                        pod.getStatus().getConditions().stream()
//                                .filter(v1PodCondition -> Objects.equals(v1PodCondition.getType(), "PodScheduled"))
//                                .findFirst()
//                                .map(v1PodCondition -> Objects.equals(v1PodCondition.getStatus(), "False")
//                                        && Objects.equals(v1PodCondition.getReason(), "Unschedulable"))
//                                .orElse(Boolean.FALSE))
//                .collect(Collectors.groupingBy(
//                        pod -> pod.getMetadata().getLabels().get(OperatorLabels.RACK),
//                        TreeMap::new,
//                        Collectors.toList()));
//    }
    
    
    /**
     * Scale up a specific rack (+1)
     */
    private void scaleUp(DataCenter dataCenter, String rack, TreeMap<String, V1StatefulSet> existingStsMap, TreeMap<String, V1StatefulSet> newtStsMap) throws ApiException {
        
        dataCenter.getStatus().setPhase(DataCenterPhase.SCALING_UP);
    
        final V1StatefulSet statefulSetToScale = newtStsMap.get(rack);
        // ensure we scale only +1
        final int replicas = existingStsMap.get(rack).getSpec().getReplicas() + 1;
        statefulSetToScale.getSpec().setReplicas(replicas);
        
        logger.info("Scaling up sts {} to {} replicas", statefulSetToScale.getMetadata().getName(), replicas);
        
        appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
    }
    
    /**
     * Scale up a specific rack (+1)
     */
    private void scaleDown(DataCenter dataCenter, String rack,
                           TreeMap<String, V1StatefulSet> existingStsMap,
                           TreeMap<String, V1StatefulSet> newtStsMap,
                           Map<ElassandraNodeStatus, List<String>> podsByStatus) throws ApiException, MalformedURLException, StrapkopException {
    
        
        dataCenter.getStatus().setPhase(DataCenterPhase.SCALING_DOWN);
        
        final V1StatefulSet statefulSetToScale = newtStsMap.get(rack);
        final int replicas = existingStsMap.get(rack).getSpec().getReplicas() - 1;
        // ensure we scale only -1
        statefulSetToScale.getSpec().setReplicas(replicas);
    
        logger.debug("Scaling down sts {} to {}", statefulSetToScale.getMetadata().getName(), replicas);
        
        // the name of the pod to remove
        final String podName = statefulSetToScale.getMetadata().getName() + "-" + replicas;
        
        if (podsByStatus.get(ElassandraNodeStatus.NORMAL).contains(podName)) {
            logger.info("Scaling down sts {} to {}, decommissioning {}", statefulSetToScale.getMetadata().getName(), replicas, podName);
            
            // blocking call to decommission, max 5 times, with 2 second delays between each try
            Throwable throwable = sidecarClientFactory.clientForPod(new ElassandraPod(dataCenter, podName)).decommission().retryWhen(errors -> errors
                    .zipWith(Flowable.range(1, 5), (n, i) -> i)
                    .flatMap(retryCount -> Flowable.timer(2, TimeUnit.SECONDS))
            ).blockingGet();
            
            if (throwable != null) {
                logger.error("failed to decommission pod={}", podName, throwable);
                dataCenter.getStatus().setLastErrorMessage(throwable.getMessage());
            }
        }
        else if (podsByStatus.get(ElassandraNodeStatus.DECOMMISSIONED).contains(podName)) {
            logger.info("Scaling down sts {} to {}, removing {}", statefulSetToScale.getMetadata().getName(), replicas, podName);
            appsApi.replaceNamespacedStatefulSet(statefulSetToScale.getMetadata().getName(), statefulSetToScale.getMetadata().getNamespace(), statefulSetToScale, null, null);
        }
        else {
            throw new StrapkopException("unreachable");
        }
    }
    
    public void updateRack(DataCenter dataCenter, V1StatefulSet newStatefulSet) throws ApiException {
        dataCenter.getStatus().setPhase(DataCenterPhase.UPDATING);
        logger.info("Update spec of sts={} (rolling restart)", newStatefulSet.getMetadata().getName());
        logger.debug("Updating sts={}", newStatefulSet);
        appsApi.replaceNamespacedStatefulSet(newStatefulSet.getMetadata().getName(), newStatefulSet.getMetadata().getNamespace(), newStatefulSet, null, null);
    }
    
    private void updateDatacenterStatus(DataCenter dataCenter, TreeMap<String, V1StatefulSet> existingStsMap,
                                        TreeMap<String, V1StatefulSet> newtStsMap,
                                        Map<ElassandraNodeStatus, List<String>> podsByStatus,
                                        Map<Boolean, List<String>> rackByReadiness,
                                        Map<RackMode, TreeSet<String>> racksByRackMode) {
    
        // set replicas status
        Tuple2<Integer,Integer> replicasStatus = existingStsMap.values().stream()
                .map(V1StatefulSet::getStatus)
                .filter(Objects::nonNull)
                .map(status -> Tuple.of(
                        Optional.ofNullable(status.getReplicas()).orElse(0),
                        Optional.ofNullable(status.getReadyReplicas()).orElse(0)))
                .reduce((t1, t2) -> Tuple.of(
                        t1._1 + t2._1,
                        t1._2 + t2._2))
                .orElseGet(() -> Tuple.of(0, 0));
        
        dataCenter.getStatus()
                .setReplicas(replicasStatus._1)
                .setReadyReplicas(replicasStatus._2)
                .setJoinedReplicas(podsByStatus.get(ElassandraNodeStatus.NORMAL).size());

        
        // initialize pod statuses
        final Map<String, ElassandraNodeStatus> podStatuses = new HashMap<>();
        dataCenter.getStatus().setElassandraNodeStatuses(podStatuses);
    
        // initialize rack statuses
        final List<RackStatus> rackStatuses = new ArrayList<>();
        dataCenter.getStatus().setRackStatuses(rackStatuses);

        // current bootstrapped rack
        Map<String, Boolean> rackSeedBootstraped = dataCenter.getStatus().getRackStatuses()
                .stream().collect(Collectors.toMap(s -> s.getName(), s -> s.getSeedBootstrapped()));

        // for each rack
        for (Map.Entry<String, V1StatefulSet> entry : newtStsMap.entrySet()) {
            String rack = entry.getKey();
            V1StatefulSet sts = entry.getValue();

            // pod-0 in the rack
            String pod0 = OperatorNames.podName(dataCenter, rack, 0);
            ElassandraNodeStatus ens = this.elassandraNodeStatusCache.get(new ElassandraPod(dataCenter, pod0));
            logger.trace("dc={} rack={} pod={} status={}", dataCenter.getMetadata().getName(), rack, pod0, ens);

            // set rack status
            rackStatuses.add(new RackStatus()
                    .setName(rack)
                    .setReady(rackByReadiness.get(true).contains(rack))
                    .setMode(racksByRackMode.entrySet().stream()
                            .filter(e -> e.getValue().contains(rack))
                            .map(Map.Entry::getKey)
                            .findFirst().orElse(RackMode.UNKNOWN))
                    .setReplicas(existingStsMap.get(rack).getSpec().getReplicas())
                    // become bootstrapped when pod-0 has been seen NORMAL.
                    .setSeedBootstrapped(rackSeedBootstraped.getOrDefault(rack, false) || ElassandraNodeStatus.NORMAL.equals(ens))
            );
            
            // set pod statuses
            final int replicas = Integer.max(
                    existingStsMap.get(rack).getSpec().getReplicas(),
                    sts.getSpec().getReplicas()
            );
            
            for (int i = 0; i < replicas; i++) {
                String podName = OperatorNames.podName(dataCenter, rack, i);
                podStatuses.put(podName, elassandraNodeStatusCache.getOrDefault(new ElassandraPod(dataCenter, podName), ElassandraNodeStatus.UNKNOWN));
            }
        }
    }
}