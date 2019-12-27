package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskStatus;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class TaskReconcilier extends Reconcilier<Tuple2<TaskReconcilier.Action, Task>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    final K8sResourceUtils k8sResourceUtils;
    private final String taskType;
    
    TaskReconcilier(ReconcilierObserver reconcilierObserver, String taskType, K8sResourceUtils k8sResourceUtils) {
        super(reconcilierObserver);
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskType = taskType;
    }
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract Completable doTask(Task task, DataCenter dc) throws Exception;

    protected Completable validTask(Task task, DataCenter dc) throws Exception {
        return Completable.complete();
    }

    @Override
    public Completable reconcile(final Tuple2<Action, Task> item) throws Exception {

        final Task task = item._2;
        
        if (item._1.equals(Action.SUBMIT)) {
            logger.debug("processing a task submit request for {} in thread {}", task.getMetadata().getName(), Thread.currentThread().getName());
            return processSubmit(task);
        }

        if (item._1.equals(Action.CANCEL)) {
            logger.warn("task cancel for {} in thread {} (NOT IMPLEMENTED)", task.getMetadata().getName(), Thread.currentThread().getName());
            // TODO: implement cancel
        }
        return Completable.complete();
    }
    
    public Completable prepareSubmitCompletable(Task task) throws Exception {
        return this.reconcile(new Tuple2<>(Action.SUBMIT, task));
    }
    
    // TODO: implement task cancellation
    public Completable prepareCancelCompletable(Task task) throws Exception {
        return this.reconcile(new Tuple2<>(Action.CANCEL, task));
    }

    Completable processSubmit(Task task) throws Exception {
        logger.info("processing {} task submit", taskType);

        // create status if necessary
        if (task.getStatus() == null) {
            task.setStatus(new TaskStatus());
        }

        // fetch corresponding dc
        return fetchDataCenter(task)
                .flatMap(dc -> reconcilierObserver.onReconciliationBegin().toSingleDefault(dc))
                .flatMap(dc -> {
                    if (task.getStatus().getPhase() != null && isTerminated(task)) {
                        logger.debug("task {} was terminated", task.getMetadata().getName());
                        return ensureUnlockDc(task, dc).toSingleDefault(dc);
                    } else {
                        return Single.just(dc);
                    }
                })
                .flatMapCompletable(dc -> {


                    if (!ensureDcIsReady(task, dc) && task.getSpec().isExclusive()) {
                        if (task.getStatus().getPhase() == null) {
                            logger.info("dc {} is not ready for {} operation, delaying.", dc.getMetadata().getName(), taskType);
                            task.getStatus().setPhase(TaskPhase.WAITING);
                            return k8sResourceUtils.updateTaskStatus(task);
                        } else {
                            logger.warn("interrupting current {} operation on dc {} because of invalid dc status", taskType, dc.getMetadata().getName());
                            task.getStatus().setPhase(TaskPhase.FAILED);
                            return ensureUnlockDc(task, dc);
                        }
                    }

                    if (!task.getSpec().isExclusive() && task.getStatus().getPhase() != null) {
                        // STARTED non exclusive task execute this to avoid passing in STARTED State another time.
                        // TestRunner use this second call to start the first step of the test suite
                        if (task.getStatus().getPhase().equals(TaskPhase.STARTED)) {
                            return validTask(task, dc);
                        } else {
                            return Completable.complete();
                        }
                    } else {
                        logger.debug("dc {} is ready to process {} {}", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
                        // lock the dc if not already done
                        return ensureLockDc(task, dc)
                                .andThen(k8sResourceUtils.updateTaskStatus(task, TaskPhase.STARTED))
                                .andThen(doTask(task, dc));
                    }

                })
                .doOnError(t -> { if (!(t instanceof ReconcilierShutdownException)) reconcilierObserver.failedReconciliationAction(); })
                .doOnComplete(reconcilierObserver.endReconciliationAction());
    }

    private boolean isTerminated(Task task) {
        return task.getStatus() != null &&
                task.getStatus().getPhase() != null &&
                task.getStatus().getPhase().isTerminated();
    }


    Single<DataCenter> fetchDataCenter(Task task) throws ApiException {
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
    
    private Completable ensureLockDc(Task task, DataCenter dc) throws ApiException {
        
        if (!task.getSpec().isExclusive() || Objects.equals(dc.getStatus().getCurrentTask(), task.getMetadata().getName())) {
            return Completable.complete();
        }
        
        logger.debug("locking dc {} for task {} {}", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
        dc.getStatus().setCurrentTask(task.getMetadata().getName());
        dc.getStatus().setPhase(DataCenterPhase.EXECUTING_TASK);
        return k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement();
    }
    
    void initializePodMap(Task task, DataCenter dc) {
        if (task.getStatus().getPods() == null) {
            task.getStatus().setPods(new HashMap<>());
        }
        
        for (Map.Entry<String, ElassandraNodeStatus> entry : dc.getStatus().getElassandraNodeStatuses().entrySet()) {
            task.getStatus().getPods().put(entry.getKey(), TaskPhase.WAITING);
        }
    }

    Completable ensureUnlockDc(Task task, DataCenter dc) throws ApiException {
        // TODO: not sure if we need an intermediate phase like "TASK_EXECUTED_PLEASE_CHECK_EVERYTHING_IS_RUNNING..."
        if (task.getSpec().isExclusive() && Objects.equals(task.getMetadata().getName(), dc.getStatus().getCurrentTask())) {
            logger.debug("unlocking dc {} after task {} {} terminated", dc.getMetadata().getName(), taskType, task.getMetadata().getName());
            dc.getStatus().setPhase(DataCenterPhase.RUNNING);
            dc.getStatus().setCurrentTask("");
            // lock and unlock can't be executed in the same reconciliation (it will conflict when updating status otherwise)
            return k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement();
        }
        return Completable.complete();
    }
    
}
