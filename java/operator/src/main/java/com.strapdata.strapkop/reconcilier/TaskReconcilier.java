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
import io.kubernetes.client.ApiException;
import io.reactivex.Completable;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

public abstract class TaskReconcilier extends Reconcilier<Tuple2<TaskReconcilier.Action, Task>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    static Set<TaskPhase> terminatedPhases = EnumSet.of(TaskPhase.SUCCEED, TaskPhase.FAILED);
    final K8sResourceUtils k8sResourceUtils;
    private final String taskType;
    
    public TaskReconcilier(String taskType, K8sResourceUtils k8sResourceUtils) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskType = taskType;
    }
    
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract void doTask(Task task, DataCenter dc) throws Exception;

    @Override
    void reconcile(final Tuple2<Action, Task> item) {
        
        final Task task = item._2;
        
        if (item._1.equals(Action.SUBMIT)) {
            logger.debug("processing a task submit request for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            try {
                processSubmit(task);
            } catch (Exception e) {
                logger.error("error while submitting task {}", task.getMetadata().getName(), e);
            }
        }
        
        else if (item._1.equals(Action.CANCEL)) {
            logger.warn("task cancel for {} in thread {} (NOT IMPLEMENTED)", task.getMetadata().getName(), Thread.currentThread().getName());
            // TODO: implement cancel
        }
    }
    
    public Completable prepareSubmitCompletable(Task task) {
        return this.asCompletable(new Tuple2<>(Action.SUBMIT, task));
    }
    
    public Completable prepareCancelCompletable(Task task) {
        return this.asCompletable(new Tuple2<>(Action.CANCEL, task));
    }
    
    void processSubmit(Task task) throws Exception {
        logger.info("processing {} task submit", taskType);
        
        // fetch corresponding dc
        final DataCenter dc = fetchDataCenter(task);
        
        // create status if necessary
        if (task.getStatus() == null) {
            task.setStatus(new TaskStatus());
        }
        
        // abort if the task is terminated already
        if (task.getStatus().getPhase() != null && terminatedPhases.contains(task.getStatus().getPhase())) {
            logger.debug("task {} was terminated", task.getMetadata().getName());
            ensureUnlockDc(task, dc);
            return ;
        }
        
        // ensure the dc is in a correct state to continue
        if (!ensureDcIsReady(task, dc)) {
            if (task.getStatus().getPhase() == null) {
                logger.info("dc {} is not ready for {} operation, delaying.", dc.getMetadata().getName(), taskType);
                task.getStatus().setPhase(TaskPhase.WAITING);
            }
            else {
                logger.warn("interrupting current {} operation on dc {} because of invalid dc status",
                        taskType, dc.getMetadata().getName());
                task.getStatus().setPhase(TaskPhase.FAILED);
                ensureUnlockDc(task, dc);
            }
            k8sResourceUtils.updateTaskStatus(task);
            return ;
        }
        logger.debug("dc {} is ready to process {} {}", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
        
        // lock the dc if not already done
        ensureLockDc(task, dc);
    
        if (task.getStatus().getPhase() == null) {
            task.getStatus().setPhase(TaskPhase.STARTED);
        }
        
        doTask(task, dc);
    }
    
    
    DataCenter fetchDataCenter(Task task) throws ApiException {
        final Key dcKey =  new Key(
                OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                task.getMetadata().getNamespace()
        );
        
        return k8sResourceUtils.readDatacenter(dcKey);
    }
    
    boolean ensureDcIsReady(Task task, DataCenter dc) {
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
    
    void ensureLockDc(Task task, DataCenter dc) throws ApiException {
        // TODO: move the locking logic on parent class for other tasks
        
        if (Objects.equals(dc.getStatus().getCurrentTask(), task.getMetadata().getName())) {
            return ;
        }
        
        logger.debug("locking dc {} for task {} {}", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
        dc.getStatus().setCurrentTask(task.getMetadata().getName());
        dc.getStatus().setPhase(DataCenterPhase.EXECUTING_TASK);
        k8sResourceUtils.updateDataCenterStatus(dc);
    }
    
    void initializePodMap(Task task, DataCenter dc) {
        if (task.getStatus().getPods() == null) {
            task.getStatus().setPods(new HashMap<>());
        }
        
        for (int i = 0; i < dc.getSpec().getReplicas(); i++) {
            final String podName = OperatorNames.podName(dc, i);
            task.getStatus().getPods().put(podName, TaskPhase.WAITING);
        }
    }
    
    void ensureUnlockDc(Task task, DataCenter dc) throws ApiException {
        // TODO: not sure if we need an intermediate phase like "TASK_EXECUTED_PLEASE_CHECK_EVERYTHING_IS_RUNNING..."
        
        if (Objects.equals(task.getMetadata().getName(), dc.getStatus().getCurrentTask())) {
            logger.debug("unlocking dc {} after task {} {} terminated", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
            dc.getStatus().setPhase(DataCenterPhase.RUNNING);
            dc.getStatus().setCurrentTask("");
            // lock and unlock can't be executed in the same reconciliation (it will conflict when updating status otherwise)
            k8sResourceUtils.updateDataCenterStatus(dc);
        }
    }
    
}
