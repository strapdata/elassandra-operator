package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.cassandra.Operation;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public abstract class TaskReconcilier extends Reconcilier<Task> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    final K8sResourceUtils k8sResourceUtils;
    final String taskType;
    final MeterRegistry meterRegistry;
    final DataCenterController dataCenterController;
    final DataCenterCache dataCenterCache;
    final OperatorConfig operatorConfig;
    private volatile int runningTaskCount = 0;
    public final Scheduler tasksScheduler;

    TaskReconcilier(ReconcilierObserver reconcilierObserver,
                    String taskType,
                    final OperatorConfig operatorConfig,
                    final K8sResourceUtils k8sResourceUtils,
                    final MeterRegistry meterRegistry,
                    final DataCenterController dataCenterController,
                    final DataCenterCache dataCenterCache,
                    ExecutorFactory executorFactory,
                    @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver);
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskType = taskType;
        this.meterRegistry = meterRegistry;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.operatorConfig = operatorConfig;
        this.tasksScheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
    }

    protected abstract Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws Exception;

    protected Completable validTask(final DataCenter dc, final Task task) throws Exception {
        return Completable.complete();
    }

    @Override
    public Completable reconcile(final Task task) throws Exception {
        DataCenter dc = dataCenterCache.get(new Key(task.getMetadata().getLabels().get(OperatorLabels.PARENT), task.getMetadata().getNamespace()));
        Operation op = new Operation().withSubmitDate(new Date()).withDesc("task-"+task.getMetadata().getName());
        final DataCenterStatus dataCenterStatus = dc.getStatus();
        dataCenterStatus.setCurrentOperation(op);

        logger.debug("datacenter={} task={} processing", dc.id(), task.id());

        if (task.getStatus() == null)
            task.setStatus(new TaskStatus());

        // failed when datacenter not found => task failed
        return validTask(dc, task)
                .andThen(listPods(task, dc).flatMapCompletable(pods -> doTask(dc, dataCenterStatus, task, pods)))     // update DC and task status
                .andThen(reconcileDcWhenDone(dc, task))
                .onErrorResumeNext(t -> {
                    logger.error("task={} FAILED due to error:", task.id(), t);
                    task.setStatus(new TaskStatus().setPhase(TaskPhase.FAILED).setLastMessage(t.getMessage()));
                    if (!(t instanceof ApiException)) {
                        // try to update etcd again !
                        return k8sResourceUtils.updateTaskStatus(task).ignoreElement();
                    }
                    return Completable.complete();
                });
    }

    Completable reconcileDcWhenDone(DataCenter dataCenter, Task task) throws Exception {
        return reconcileDataCenterWhenDone() ?
                this.dataCenterController.taskDone(dataCenter, task) :
                Completable.complete();
    }

    public Completable updateDatacenterStatus(final DataCenter dc, final DataCenterStatus dataCenterStatus) throws ApiException {
        return k8sResourceUtils.updateDataCenterStatus(dc, dataCenterStatus).ignoreElement();
    }

    public Completable finalizeTaskStatus(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, TaskPhase taskPhase0) throws ApiException {
        TaskStatus taskStatus = task.getStatus();
        TaskPhase taskPhase = taskPhase0;
        for (Map.Entry<String, TaskPhase> e : taskStatus.getPods().entrySet()) {
            if (e.getValue().equals(TaskPhase.FAILED)) {
                taskPhase = TaskPhase.FAILED;
                break;
            }
        }
        taskStatus.setPhase(taskPhase);
        logger.debug("task={} finalized phase={}", task.id(), taskPhase);
        return k8sResourceUtils.updateTaskStatus(task)
                .flatMapCompletable(p -> {
                    if (task.getStatus() == null)
                        task.setStatus(new TaskStatus().withStartDate(new Date()));
                    if (task.getStatus().getStartDate() == null)
                        task.getStatus().setStartDate(new Date());
                    long startTime = task.getStatus().getStartDate().getTime();
                    long endTime = System.currentTimeMillis();
                    task.getStatus().setDurationInMs(endTime - startTime);

                    Operation currentOperation = dataCenterStatus.getCurrentOperation();
                    if (currentOperation == null) {
                        currentOperation = new Operation().withDesc("task-" + task.getMetadata().getName()).withSubmitDate(new Date());
                    }
                    currentOperation.setPendingInMs(startTime - currentOperation.getSubmitDate().getTime());
                    currentOperation.setDurationInMs(endTime - startTime);

                    List<Operation> history = dataCenterStatus.getOperationHistory();
                    history.add(0, currentOperation);
                    if (history.size() > operatorConfig.getOperationHistoryDepth())
                        history.remove(operatorConfig.getOperationHistoryDepth());
                    dataCenterStatus.setOperationHistory(history);

                    logger.debug("update status taskStatus={} datacenterStatus={}", task.getStatus(), dataCenterStatus);
                    return k8sResourceUtils.updateDataCenterStatus(dc, dataCenterStatus).ignoreElement();
                });
    }

    /**
     * May be overriden by TaskReconcilier
     *
     * @param task
     * @param dc
     * @return
     */
    public abstract Single<Iterable<V1Pod>> listPods(Task task, DataCenter dc);

    // a possible implementation of initializePodMap
    public Single<Iterable<V1Pod>> initializePodMapWithWaitingStatus(Task task, DataCenter dc) {
        final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(
                OperatorLabels.MANAGED_BY, "elassandra-operator",
                OperatorLabels.PARENT, dc.getMetadata().getName(),
                OperatorLabels.APP, "elassandra"
        ));
        return Single.fromCallable(new Callable<Iterable<V1Pod>>() {
            @Override
            public Iterable<V1Pod> call() throws Exception {
                return k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), null, labelSelector);
            }
        });
    }

    /**
     * Should we reconcile DC when task is done (ex: rebuild-stream)
     *
     * @return
     */
    public boolean reconcileDataCenterWhenDone() {
        return false;
    }
}