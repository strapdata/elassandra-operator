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
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
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
    
    protected abstract Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception;

    protected Completable validTask(TaskWrapper task, DataCenter dc) throws Exception {
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
        logger.info("processing type={} task={}", taskType, task);

/*        // create status if necessary
        if (task.getStatus() == null) {
            task.setStatus(new TaskStatus());
        }*/

        TaskWrapper taskWrapper = new TaskWrapper(task);

        return fetchTask(taskWrapper)// refresh the task to avoid conflict and multiple execution
                .flatMap((t) -> fetchDataCenter(taskWrapper))
                .flatMapCompletable(dc -> {
                    // finish on going task
                    if (taskWrapper.getTask().getStatus().getPhase() != null && isTerminated(taskWrapper.getTask())) {
                        logger.debug("task {} was terminated", task.getMetadata().getName());
                        return ensureUnlockDc(dc, taskWrapper);
                    }

                    // dc ready ?
                    if (!ensureDcIsReady(taskWrapper, dc)) {
                        return updateTaskStatus(dc, taskWrapper, TaskPhase.WAITING);
                    }

                    switch(taskWrapper.task.getStatus().getPhase()) {
                        case WAITING:
                            return validTask(taskWrapper, dc)
                                    .subscribeOn(Schedulers.io())
                                    .andThen(ensureLockDc(taskWrapper, dc))
                                    .andThen(initializePodMap(taskWrapper, dc))
                                    .andThen(updateTaskStatus(dc, taskWrapper, TaskPhase.RUNNING))
                                    .andThen(doTask(taskWrapper, dc)
                                            .onErrorReturn(t -> TaskPhase.FAILED)
                                            .flatMapCompletable(s -> updateTaskStatus(dc, taskWrapper, s)))
                                    .andThen(ensureUnlockDc(dc, taskWrapper));
                        case RUNNING:
                            return ensureLockDc(taskWrapper, dc)
                                    .subscribeOn(Schedulers.io())
                                    .andThen(doTask(taskWrapper, dc)
                                            .onErrorReturn(t -> TaskPhase.FAILED)
                                            .flatMapCompletable(s -> updateTaskStatus(dc, taskWrapper, s)))
                                    .andThen(ensureUnlockDc(dc, taskWrapper));
                        default:
                            // nothing to do
                            return Completable.complete();
                    }
                })
                // failed when datacenter not found => task failed
                .onErrorResumeNext(t -> {
                    logger.error("task IGNORED dur to error:", t);
                    taskWrapper.getTask().setStatus(new TaskStatus().setPhase(TaskPhase.IGNORED).setLastMessage(t.getMessage()));
                    return k8sResourceUtils.updateTaskStatus(taskWrapper);
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

    public Completable updateTaskStatus(DataCenter dc, TaskWrapper taskWrapper, TaskPhase phase) throws ApiException {
        final Task task = taskWrapper.getTask();
        logger.debug("Update task {}/{} in dc name={} namespace={} phase={}",
                taskType, task.getMetadata().getName(), dc.getMetadata().getName(), dc.getMetadata().getNamespace(), phase);
        task.getStatus().setPhase(phase);
        // TODO update wrapper
        return k8sResourceUtils.updateTaskStatus(taskWrapper);
    }

    public Completable updateTaskPodStatus(DataCenter dc, TaskWrapper taskWrapper, TaskPhase phase, String pod, TaskPhase podPhase) throws ApiException {
        return updateTaskPodStatus(dc, taskWrapper, phase, pod, podPhase, null);
    }

    public Completable updateTaskPodStatus(DataCenter dc, TaskWrapper taskWrapper, TaskPhase phase, String pod, TaskPhase podPhase, String msg) throws ApiException {
        Task task = taskWrapper.getTask();
        TaskStatus taskStatus = task.getStatus();
        taskStatus.getPods().put(pod, podPhase);
        taskStatus.setPhase(phase);
        if (msg != null)
            taskStatus.setLastMessage(msg);
        return k8sResourceUtils.updateTaskStatus(taskWrapper);
    }

    public Single<TaskPhase> finalizeTaskStatus(DataCenter dc, TaskWrapper taskWrapper) throws ApiException {
        Task task = taskWrapper.getTask();
        TaskStatus taskStatus = task.getStatus();
        TaskPhase taskPhase = TaskPhase.SUCCEED;
        for (Map.Entry<String, TaskPhase> e : taskStatus.getPods().entrySet()) {
            if (e.getValue().equals(TaskPhase.FAILED)) {
                taskPhase = TaskPhase.FAILED;
                break;
            }
        }
        taskStatus.setPhase(taskPhase);
        return k8sResourceUtils.updateTaskStatus(taskWrapper).toSingleDefault(taskPhase);
    }

    public Single<TaskPhase> finalizeTaskStatus(DataCenter dc, TaskWrapper taskWrapper, TaskPhase taskPhase) throws ApiException {
        Task task = taskWrapper.getTask();
        TaskStatus taskStatus = task.getStatus();
        taskStatus.setPhase(taskPhase);
        return k8sResourceUtils.updateTaskStatus(taskWrapper).toSingleDefault(taskPhase);
    }

    Single<DataCenter> fetchDataCenter(TaskWrapper taskWrapper) throws ApiException {
        final Task task = taskWrapper.getTask();
        final Key dcKey =  new Key(
                OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                task.getMetadata().getNamespace()
        );
        return k8sResourceUtils.readDatacenter(dcKey);
    }

    Single<TaskWrapper>  fetchTask(TaskWrapper taskWrapper) throws ApiException {
        final Task task = taskWrapper.getTask();
        return k8sResourceUtils.readTask(task.getMetadata().getNamespace(), task.getMetadata().getName())
                .map((freshTask) -> {
                    freshTask.ifPresent((t) -> {
                            // create status if necessary
                            if (t.getStatus() == null) {
                                t.setStatus(new TaskStatus());
                            }
                            taskWrapper.updateTaskRef(t);
                    });
                    return taskWrapper;
                });
    }

    boolean ensureDcIsReady(TaskWrapper taskWrapper, DataCenter dc) {
        final Task task = taskWrapper.getTask();
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

    protected Completable ensureLockDc(TaskWrapper taskWrapper, DataCenter dc) throws ApiException {
        final Task task = taskWrapper.getTask();

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

    protected Completable ensureUnlockDc(DataCenter dc, TaskWrapper taskWrapper) throws ApiException {
        final Task task = taskWrapper.getTask();
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
    public abstract BlockReason blockReason();

    protected Completable initializePodMap(TaskWrapper taskWrapper, DataCenter dc) {
        for (Map.Entry<String, ElassandraNodeStatus> entry : dc.getStatus().getElassandraNodeStatuses().entrySet()) {
            if (!entry.getValue().equals(ElassandraNodeStatus.UNKNOWN)) {
                // only add reachable nodes (usually UNKNWON is used for unreachable or non bootstrapped node)
                taskWrapper.task.getStatus().getPods().put(entry.getKey(), TaskPhase.WAITING);
            }
        }
        return Completable.complete();
    }

    public final static class TaskWrapper {
        private Task task;

        public TaskWrapper(Task task) {
            this.task = task;
        }

        public Task getTask() {
            return task;
        }

        public void updateTaskRef(Task task) {
            this.task = task;
        }
    }
}