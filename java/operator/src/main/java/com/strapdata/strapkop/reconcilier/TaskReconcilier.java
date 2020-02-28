package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.Block;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public abstract class TaskReconcilier extends Reconcilier<Tuple2<TaskReconcilier.Action, Task>> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    final K8sResourceUtils k8sResourceUtils;
    final String taskType;
    final MeterRegistry meterRegistry;
    final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;

    private volatile int runningTaskCount = 0;

    TaskReconcilier(ReconcilierObserver reconcilierObserver,
                    String taskType,
                    final K8sResourceUtils k8sResourceUtils,
                    final MeterRegistry meterRegistry,
                    final DataCenterUpdateReconcilier dataCenterUpdateReconcilier
                    ) {
        super(reconcilierObserver);
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskType = taskType;
        this.meterRegistry = meterRegistry;
        this.dataCenterUpdateReconcilier= dataCenterUpdateReconcilier;
    }
    
    enum Action {
        SUBMIT, CANCEL
    }
    
    protected abstract Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws Exception;

    protected Completable validTask(final Task task, final DataCenter dc) throws Exception {
        return Completable.complete();
    }

    @Override
    public Completable reconcile(final Tuple2<Action, Task> item) throws Exception {

        final Task task = item._2;
        
        if (item._1.equals(Action.SUBMIT)) {
            logger.debug("task={} submit", task.id());
            return processSubmit(task);
        }

        if (item._1.equals(Action.CANCEL)) {
            logger.warn("task={} cancel (NOT IMPLEMENTED)", task.id());
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

    Completable processSubmit(Task task0) throws Exception {
        return Single.zip(k8sResourceUtils.readTask(task0.getMetadata().getNamespace(), task0.getMetadata().getName()),
                          fetchDataCenter(task0),
                (t,dc) -> new Tuple2<>(t,dc))
                .flatMapCompletable(tuple -> {
                    DataCenter dc = tuple._2;
                    Task task = tuple._1.get();
                    logger.debug("datacenter={} task={} processing", dc.id(), task);

                    if (task.getStatus() == null)
                        task.setStatus(new TaskStatus());

                    // finish on going task
                    if (task.getStatus().getPhase() != null && isTerminated(task)) {
                        logger.debug("task={} was terminated", task.id());
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
                                    .andThen(doTask(task, dc).flatMapCompletable(s -> updateTaskStatus(dc, task, s)))
                                    .andThen(ensureUnlockDc(dc, task))
                                    .andThen(reconcileDcWhenDone(dc));
                        case RUNNING:
                            return ensureLockDc(task, dc)
                                    .subscribeOn(Schedulers.io())
                                    .andThen(doTask(task, dc).flatMapCompletable(s -> updateTaskStatus(dc, task, s)))
                                    .andThen(ensureUnlockDc(dc, task))
                                    .andThen(reconcileDcWhenDone(dc));
                        default:
                            // nothing to do
                            logger.debug("task={} phase={} nothing to do", task.id(), task.getStatus().getPhase());
                            return Completable.complete();
                    }
                })
                // failed when datacenter not found => task failed
                .onErrorResumeNext(t -> {
                    logger.error("task={} IGNORED due to error:", task0.id(), t);
                    task0.setStatus(new TaskStatus().setPhase(TaskPhase.IGNORED).setLastMessage(t.getMessage()));
                    if (!(t instanceof ApiException)) {
                        // try to update etcd again !
                        return k8sResourceUtils.updateTaskStatus(task0).ignoreElement();
                    }
                    return Completable.complete();
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

    Completable reconcileDcWhenDone(DataCenter dataCenter) throws ApiException {
        return reconcileDataCenterWhenDone() ?
                this.dataCenterUpdateReconcilier.reconcile(new Key(dataCenter.getMetadata())) :
                Completable.complete();
    }

    public Completable updateTaskStatus(final DataCenter dc, final Task task, TaskPhase phase) throws ApiException {
        logger.debug("datacenter={} task={} type={} phase={}", dc.id(), task.id(), taskType, phase);
        task.getStatus().setPhase(phase);
        // TODO update wrapper
        return k8sResourceUtils.updateTaskStatus(task).ignoreElement();
    }

    public Completable updateTaskPodStatus(final DataCenter dc, final Task task, TaskPhase phase, String pod, TaskPhase podPhase) throws ApiException {
        return updateTaskPodStatus(dc, task, phase, pod, podPhase, null);
    }

    public Completable updateTaskPodStatus(final DataCenter dc, final Task task, TaskPhase phase, String pod, TaskPhase podPhase, String msg) throws ApiException {
        TaskStatus taskStatus = task.getStatus();
        taskStatus.getPods().put(pod, podPhase);
        taskStatus.setPhase(phase);
        if (msg != null)
            taskStatus.setLastMessage(msg);
        return k8sResourceUtils.updateTaskStatus(task).ignoreElement();
    }

    public Single<TaskPhase> finalizeTaskStatus(final DataCenter dc, final Task task) throws ApiException {
        TaskStatus taskStatus = task.getStatus();
        TaskPhase taskPhase = TaskPhase.SUCCEED;
        for (Map.Entry<String, TaskPhase> e : taskStatus.getPods().entrySet()) {
            if (e.getValue().equals(TaskPhase.FAILED)) {
                taskPhase = TaskPhase.FAILED;
                break;
            }
        }
        taskStatus.setPhase(taskPhase);
        logger.debug("task={} update", task);
        final TaskPhase phase = taskPhase;
        return k8sResourceUtils.updateTaskStatus(task).map(o -> phase);
    }

    public Single<TaskPhase> finalizeTaskStatus(final DataCenter dc, final Task task, TaskPhase taskPhase) throws ApiException {
        TaskStatus taskStatus = task.getStatus();
        taskStatus.setPhase(taskPhase);
        logger.debug("task={} update phase={}", task.id(), taskPhase);
        final TaskPhase phase = taskPhase;
        return k8sResourceUtils.updateTaskStatus(task).map(o -> phase);
    }

    Single<DataCenter> fetchDataCenter(final Task task) throws ApiException {
        final Key dcKey =  new Key(
                OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                task.getMetadata().getNamespace()
        );
        return k8sResourceUtils.readDatacenter(dcKey);
    }

    boolean ensureDcIsReady(final Task task, DataCenter dc) {
        if (dc.getStatus() == null) {
            return false;
        }

        switch(dc.getStatus().getPhase()){
            case CREATING:
            case SCALING_UP:
            case SCALING_DOWN:
            case RUNNING:
                logger.debug("datacenter={} phase={} ready to run task={}",
                        dc.id(), dc.getStatus().getPhase(), task.id());
                return true;
            default:
                logger.debug("datacenter={} phase={} NOT ready to run task={}",
                        dc.id(), dc.getStatus().getPhase(), task.id());
                return false;
        }
    }

    /**
     * Setup a lock on datacenter status (submit DC update and wait for lock)
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     * @throws InterruptedException
     */
    protected Completable ensureLockDc(Task task, DataCenter dc) throws ApiException, InterruptedException {
        BlockReason reason = blockReason();
        return k8sResourceUtils.readDatacenter(new Key(dc.getMetadata()))
                .flatMapCompletable(dataCenter -> {
                    logger.info("datacenter={} task={} type={} starting, Locking datacenter", dc.id(), task.id(), taskType);
                    dc.getStatus().setCurrentTask(task.getMetadata().getName());
                    Block block = dc.getStatus().getBlock();
                    block.getReasons().add(reason);
                    block.setLocked(true);
                    return k8sResourceUtils.updateDataCenterStatus(dc)
                            .map(o -> {
                                logger.debug("datacenter={} task={} DC locked", dc.id(), task.id());
                                return dc;
                            }).ignoreElement();
                })
                .onErrorResumeNext(t -> {
                    logger.error("datacenter={} task={} Failed to lock dc", dc.id(), task.id());
                    return Completable.complete();
                });
    }

    protected Completable ensureUnlockDc(final DataCenter dc, final Task task) throws ApiException {
        BlockReason reason = blockReason();
        return k8sResourceUtils.readDatacenter(new Key(dc.getMetadata()))
                .flatMapCompletable(dataCenter -> {
                    logger.info("datacenter={} task={} type={} done, Unlocking  the datacenter", dc.id(), task.id(), taskType);
                    dc.getStatus().setCurrentTask("");
                    Block block = dc.getStatus().getBlock();
                    block.getReasons().remove(reason);
                    if (block.getReasons().isEmpty())
                        block.setLocked(false);
                    return k8sResourceUtils.updateDataCenterStatus(dc)
                            .map(o -> {
                                logger.debug("datacenter={} task={} DC unlocked block={}", dc.id(), task.id(), block);
                                return dc;
                            })
                            .ignoreElement();
                })
                .onErrorResumeNext(t -> {
                    logger.error("datacenter={} task={} Failed to unlock dc", dc.id(), task.id());
                    return Completable.complete();
                });
    }

    /**
     * Implementation class should return the appropriate BlockReason.
     * @return
     */
    public abstract BlockReason blockReason();

    /**
     * May be overriden by TaskReconcilier
     * @param task
     * @param dc
     * @return
     */
    public Completable initializePodMap(Task task, DataCenter dc) {
        return Completable.complete();
    }

    /**
     * Should we reconcile DC when task is done (ex: rebuild-stream)
     * @return
     */
    public boolean reconcileDataCenterWhenDone() {
        return false;
    }
}