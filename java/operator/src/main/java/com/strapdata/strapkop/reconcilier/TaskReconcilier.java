package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.Block;
import com.strapdata.model.k8s.cassandra.BlockReason;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskStatus;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Map;

public abstract class TaskReconcilier extends Reconcilier<Tuple2<TaskReconcilier.Action, Task>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    final K8sResourceUtils k8sResourceUtils;
    private final String taskType;

    @Inject
    private MeterRegistry meterRegistry;

    private volatile int runningTaskCount = 0;

    TaskReconcilier(ReconcilierObserver reconcilierObserver,
                    String taskType,
                    final K8sResourceUtils k8sResourceUtils,
                    final MeterRegistry meterRegistry
    ) {
        super(reconcilierObserver);
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskType = taskType;
        this.meterRegistry = meterRegistry;
    }
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract Single<TaskPhase> doTask(Task task, DataCenter dc) throws Exception;

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
        meterRegistry.counter("task.submit").increment();
        return this.reconcile(new Tuple2<>(Action.SUBMIT, task));
    }
    
    // TODO: implement task cancellation
    public Completable prepareCancelCompletable(Task task) throws Exception {
        meterRegistry.counter("task.cancel").increment();
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
                .flatMapCompletable(dc -> {
                    // finish on going task
                    if (task.getStatus().getPhase() != null && isTerminated(task)) {
                        logger.debug("task {} was terminated", task.getMetadata().getName());
                        return ensureUnlockDc(dc, task);
                    }

                    // dc ready ?
                    if (!ensureDcIsReady(task, dc)) {
                        return updateTaskStatus(dc, task, TaskPhase.WAITING);
                    }

                    switch(task.getStatus().getPhase()) {
                        case WAITING:
                            return validTask(task, dc)
                                    .subscribeOn(Schedulers.io())
                                    .andThen(ensureLockDc(task, dc))
                                    .andThen(initializePodMap(task, dc))
                                    .andThen(updateTaskStatus(dc, task, TaskPhase.RUNNING))
                                    .andThen(doTask(task, dc)
                                            .onErrorReturn(t -> TaskPhase.FAILED)
                                            .flatMapCompletable(s -> updateTaskStatus(dc, task, s)))
                                    .andThen(ensureUnlockDc(dc, task));
                        case RUNNING:
                            return ensureLockDc(task, dc)
                                    .subscribeOn(Schedulers.io())
                                    .andThen(doTask(task, dc)
                                            .onErrorReturn(t -> TaskPhase.FAILED)
                                            .flatMapCompletable(s -> updateTaskStatus(dc, task, s)))
                                    .andThen(ensureUnlockDc(dc, task));
                        default:
                            // nothing to do
                            return Completable.complete();
                    }
                });
    }

    private boolean isTerminated(Task task) {
        if (task.getStatus() == null || task.getStatus().getPhase() == null)
            return false;

        switch(task.getStatus().getPhase()) {
            case SUCCEED:
            case FAILED:
                return true;

            case RUNNING:
                return task.getStatus().getPods().entrySet().stream().filter(e -> {
                    return TaskPhase.WAITING.equals(e.getValue()) || TaskPhase.RUNNING.equals(e.getValue());
                }).count() == 0;

            default:
                return false;
        }
    }

    public Completable updateTaskStatus(DataCenter dc, Task task, TaskPhase phase) throws ApiException {
        logger.debug("Update task {}/{} in dc name={} namespace={} phase={}",
                taskType, task.getMetadata().getName(), dc.getMetadata().getName(), dc.getMetadata().getNamespace(), phase);
        task.getStatus().setPhase(phase);
        return k8sResourceUtils.updateTaskStatus(task);
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

        switch(dc.getStatus().getPhase()){
            case CREATING:
            case SCALING_UP:
            case SCALING_DOWN:
            case RUNNING:
                logger.debug("Dc name={} in namespace={} phase={} ready to run task={}",
                        dc.getMetadata().getName(), dc.getMetadata().getNamespace(), dc.getStatus().getPhase(), task.getMetadata().getName());
                return true;
            default:
                logger.debug("Dc name={} in namespace={} phase={} NOT ready to run task={}",
                        dc.getMetadata().getName(), dc.getMetadata().getNamespace(), dc.getStatus().getPhase(), task.getMetadata().getName());
                return false;
        }
    }

    protected Completable ensureLockDc(Task task, DataCenter dc) throws ApiException {
        BlockReason reason = blockReason();
        if (!reason.equals(BlockReason.NONE)) {
            logger.info("Locking dc name={} in namespace={} for task {} {}",
                    dc.getMetadata().getName(), dc.getMetadata().getNamespace(), taskType, task.getMetadata().getName());
            dc.getStatus().setCurrentTask(task.getMetadata().getName());
            Block block = dc.getStatus().getBlock();
            block.getReasons().add(reason);
            block.setLocked(true);
        }

        return k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement();
    }

    protected Completable ensureUnlockDc(DataCenter dc, Task task) throws ApiException {
        BlockReason reason = blockReason();
        if (!reason.equals(BlockReason.NONE)) {
            logger.debug("Unlocking datacenter name={}  in namespace={} after task {} {} terminated",
                    dc.getMetadata().getName(), dc.getMetadata().getNamespace(), taskType, task.getMetadata().getName());
            dc.getStatus().setCurrentTask("");

            Block block = dc.getStatus().getBlock();
            block.getReasons().remove(reason);
            if (block.getReasons().isEmpty())
                block.setLocked(false);

            // lock and unlock can't be executed in the same reconciliation (it will conflict when updating status otherwise)
            return k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement();
        }
        return Completable.complete();
    }

    /**
     * Implementation class should return the appropriate BlockReason.
     * @return
     */
    public BlockReason blockReason() {
        return BlockReason.NONE;
    }

    protected Completable initializePodMap(Task task, DataCenter dc) {
        for (Map.Entry<String, ElassandraNodeStatus> entry : dc.getStatus().getElassandraNodeStatuses().entrySet()) {
            task.getStatus().getPods().put(entry.getKey(), TaskPhase.WAITING);
        }
        return Completable.complete();
    }

}
