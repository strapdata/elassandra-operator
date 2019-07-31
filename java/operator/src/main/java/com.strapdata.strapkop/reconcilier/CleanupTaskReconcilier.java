package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class CleanupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    
    private static Set<TaskPhase> terminatedPhases = EnumSet.of(TaskPhase.SUCCEED, TaskPhase.FAILED);
    
    public CleanupTaskReconcilier(K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
    }
    
    @Override
    protected void processSubmit(Task task) throws ApiException {
        logger.info("processing cleanup task submit");
    
        // fetch corresponding dc
        final DataCenter dc = fetchDataCenter(task);
    
        // create status if necessary
        if (task.getStatus() == null) {
            task.setStatus(new TaskStatus());
        }

        // abort if the task is terminated already
        if (task.getStatus().getPhase() != null && terminatedPhases.contains(task.getStatus().getPhase())) {
            logger.debug("cleanup {} was terminated", task.getMetadata().getName());
            ensureUnlockDc(task, dc);
            return ;
        }
        
        // ensure the dc is in a correct state to continue
        if (!ensureDcIsReady(task, dc)) {
            if (task.getStatus().getPhase() == null) {
                logger.info("dc {} is not ready for cleanup operation, delaying.", dc.getMetadata().getName());
                task.getStatus().setPhase(TaskPhase.WAITING);
            }
            else {
                logger.warn("interrupting current cleanup operation on dc {} because of invalid dc status",
                        dc.getMetadata().getName());
                task.getStatus().setPhase(TaskPhase.FAILED);
                ensureUnlockDc(task, dc);
            }
            k8sResourceUtils.updateTaskStatus(task);
            return ;
        }
        logger.debug("dc {} is ready to process cleanup {}", dc.getMetadata().getName(), task.getMetadata().getName());

        // lock the dc if not already done
        ensureLockDc(task, dc);
        
        // if it's the first time, initialize the map of pods status
        if (task.getStatus().getPhase() == null) {
            task.getStatus().setPhase(TaskPhase.STARTED);
            initializePodMap(task, dc);
        }
        
        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // if there is no more we are done
        if (pods.isEmpty()) {
            task.getStatus().setPhase(TaskPhase.SUCCEED);
            k8sResourceUtils.updateTaskStatus(task);
            ensureUnlockDc(task, dc);
            return ;
        }
        
        // do clean up on each pod with 10 sec interval
        // TODO: maybe we should try to caught outer exception (even if we already catch inside doOnNext)
        Observable.zip(
                Observable.fromIterable(pods),
                Observable.interval(10, TimeUnit.SECONDS),
                (pod, timer) -> pod)
                .doOnNext(pod -> {
                    try {
                        final String podFqdn = String.format("%s.%s.%s.svc.cluster.local", pod,
                                OperatorNames.nodesService(dc), dc.getMetadata().getNamespace());
                        
                        final Throwable t = sidecarClientFactory.clientForHost(podFqdn).cleanup().blockingGet();
                        if (t != null) throw t;
                        task.getStatus().getPods().put(pod, TaskPhase.SUCCEED);
                    } catch (Throwable throwable) {
                        logger.error("error while executing cleanup on {}", pod, throwable);
                        task.getStatus().getPods().put(pod, TaskPhase.FAILED);
                        task.getStatus().setLastErrorMessage(throwable.getMessage());
                        task.getStatus().setPhase(TaskPhase.FAILED);
                    }
                })
                .toList().blockingGet();

        k8sResourceUtils.updateTaskStatus(task);
    }
    
    @Override
    protected void processCancel(Task task) {
        logger.debug("cleanup cancellation not implemented");
    }
    
    
    private DataCenter fetchDataCenter(Task task) throws ApiException {
        final Key dcKey =  new Key(
                OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                task.getMetadata().getNamespace()
        );
        
        return k8sResourceUtils.readDatacenter(dcKey);
    }
    
    private boolean ensureDcIsReady(Task task, DataCenter dc) {
        if (dc.getStatus() == null) {
            return false;
        }

        
        if (Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.EXECUTING_TASK)) {
            if (!Strings.isNullOrEmpty(dc.getStatus().getCurrentTask()) &&
                    !dc.getStatus().getCurrentTask().equals(task.getMetadata().getName())) {
                return false;
            }
        }
        else if (!Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING)) {
            return false;
        }
    
        return Objects.equals(dc.getStatus().getJoinedReplicas(), dc.getSpec().getReplicas()) &&
                Objects.equals(dc.getStatus().getReadyReplicas(), dc.getSpec().getReplicas());
    }
    
    private void ensureLockDc(Task task, DataCenter dc) throws ApiException {
        // TODO: move the locking logic on parent class for other tasks
        
        if (Objects.equals(dc.getStatus().getCurrentTask(), task.getMetadata().getName())) {
            return ;
        }
        
        logger.debug("locking dc {} for task cleanup {}", dc.getMetadata().getName(), task.getMetadata().getName());
        dc.getStatus().setCurrentTask(task.getMetadata().getName());
        dc.getStatus().setPhase(DataCenterPhase.EXECUTING_TASK);
        k8sResourceUtils.updateDataCenterStatus(dc);
    }
    
    private void initializePodMap(Task task, DataCenter dc) {
        if (task.getStatus().getPods() == null) {
            task.getStatus().setPods(new HashMap<>());
        }
        
        for (int i = 0; i < dc.getSpec().getReplicas(); i++) {
            final String podName = OperatorNames.podName(dc, i);
            task.getStatus().getPods().put(podName, TaskPhase.WAITING);
        }
    }
    
    private void ensureUnlockDc(Task task, DataCenter dc) throws ApiException {
        // TODO: not sure if we need an intermediate phase like "TASK_EXECUTED_PLEASE_CHECK_EVERYTHING_IS_RUNNING..."
        
        if (Objects.equals(task.getMetadata().getName(), dc.getStatus().getCurrentTask())) {
            logger.debug("unlocking dc {} after task cleanup {} terminated", dc.getMetadata().getName(), task.getMetadata().getName());
            dc.getStatus().setPhase(DataCenterPhase.RUNNING);
            dc.getStatus().setCurrentTask("");
            // lock and unlock can't be executed in the same reconciliation (it will conflict when updating status otherwise)
            k8sResourceUtils.updateDataCenterStatus(dc);
        }
    }
}
