/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class TaskReconcilier extends Reconcilier<Task> {

    private static final Logger logger = LoggerFactory.getLogger(TaskReconcilier.class);
    final K8sResourceUtils k8sResourceUtils;
    final MeterRegistry meterRegistry;
    final DataCenterController dataCenterController;
    final DataCenterCache dataCenterCache;
    final DataCenterStatusCache dataCenterStatusCache;
    final OperatorConfig operatorConfig;
    private volatile int runningTaskCount = 0;
    public final Scheduler tasksScheduler;

    TaskReconcilier(ReconcilierObserver reconcilierObserver,
                    final OperatorConfig operatorConfig,
                    final K8sResourceUtils k8sResourceUtils,
                    final MeterRegistry meterRegistry,
                    final DataCenterController dataCenterController,
                    final DataCenterCache dataCenterCache,
                    final DataCenterStatusCache dataCenterStatusCache,
                    ExecutorFactory executorFactory,
                    @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver);
        this.k8sResourceUtils = k8sResourceUtils;
        this.meterRegistry = meterRegistry;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterStatusCache = dataCenterStatusCache;
        this.operatorConfig = operatorConfig;
        this.tasksScheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
    }

    protected abstract Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws Exception;

    protected Completable validTask(final DataCenter dc, final Task task) throws Exception {
        return Completable.complete();
    }

    @Override
    public Completable reconcile(final Task task) throws Exception {
        String dcName = OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter());
        Key key = new Key(dcName, task.getMetadata().getNamespace());
        DataCenter dc = dataCenterCache.get(key);
        final DataCenterStatus dataCenterStatus = dataCenterStatusCache.get(key);

        logger.debug("datacenter={} task={} processing generation={}", dc.id(), task.id(), task.getMetadata().getGeneration());
        task.getStatus().setObservedGeneration(task.getMetadata().getGeneration());

        // failed when datacenter not found => task failed
        return validTask(dc, task)
                .andThen(init(task, dc).flatMapCompletable(pods -> doTask(dc, dataCenterStatus, task, pods)))     // update DC and task status
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

    public Completable finalizeTaskStatus(final DataCenter dc,
                                          final DataCenterStatus dataCenterStatus,
                                          final Task task,
                                          TaskPhase taskPhase0,
                                          String taskTag) throws ApiException {
        return finalizeTaskStatus(dc, dataCenterStatus, task, taskPhase0, taskTag, null);
    }

    public Completable finalizeTaskStatus(final DataCenter dc,
                                          final DataCenterStatus dataCenterStatus,
                                          final Task task,
                                          TaskPhase taskPhase0,
                                          String taskTag,
                                          Consumer<DataCenterStatus> succeedHandler
    ) throws ApiException {
        TaskStatus taskStatus = task.getStatus();
        TaskPhase taskPhase = taskPhase0;
        for (Map.Entry<String, TaskPhase> e : taskStatus.getPods().entrySet()) {
            if (e.getValue().equals(TaskPhase.FAILED)) {
                taskPhase = TaskPhase.FAILED;
                break;
            }
        }
        final TaskPhase taskPhaseFinal = taskPhase;
        taskStatus.setPhase(taskPhaseFinal);
        logger.debug("task={} finalized phase={}", task.id(), taskPhaseFinal);
        updateMetrics(task, taskTag, taskPhaseFinal.isSucceed());

        // update task duration
        long startTime = task.getStatus().getStartDate().getTime();
        long endTime = System.currentTimeMillis();
        task.getStatus().setDurationInMs(endTime - startTime);

        if (succeedHandler != null && taskPhaseFinal.isSucceed()) {
            // update dc status if task succeed
            succeedHandler.accept(dataCenterStatus);
        }
        return k8sResourceUtils.updateTaskStatus(task)
                .flatMapCompletable(p -> {
                    Operation operation = new Operation()
                            .withTriggeredBy("task " + task.getMetadata().getName())
                            .withLastTransitionTime(new Date());
                    operation.getActions().add("task " + task.getMetadata().getName());
                    operation.setPendingInMs(startTime - operation.getLastTransitionTime().getTime());
                    operation.setDurationInMs(endTime - startTime);

                    List<Operation> history = dataCenterStatus.getOperationHistory();
                    history.add(0, operation);
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
    public abstract Single<List<V1Pod>> init(Task task, DataCenter dc);

    // a possible implementation of initializePodMap
    public Single<List<V1Pod>> listAllDcPods(Task task, DataCenter dc) {
        final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(
                OperatorLabels.MANAGED_BY, "elassandra-operator",
                OperatorLabels.PARENT, dc.getMetadata().getName(),
                OperatorLabels.APP, "elassandra"
        ));
        return Single.fromCallable(new Callable<List<V1Pod>>() {
            @Override
            public List<V1Pod> call() throws Exception {
                return Lists.newArrayList(k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), null, labelSelector));
            }
        });
    }

    public Single<List<V1Pod>>  getElassandraRunningPods(DataCenter dc) {
        final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(
                OperatorLabels.MANAGED_BY, "elassandra-operator",
                OperatorLabels.PARENT, dc.getMetadata().getName(),
                OperatorLabels.APP, "elassandra"
        ));
        return Single.fromCallable(new Callable<List<V1Pod>>() {
            @Override
            public List<V1Pod> call() throws Exception {
                return Lists.newArrayList(k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), "status.phase=Running", labelSelector));
            }
        });
    }

    public List<V1Pod> initTaskStatusPodMap(Task task, List<V1Pod> pods) {
        task.getStatus().setPods(pods.stream().collect(Collectors.toMap(p -> p.getMetadata().getName(), p -> TaskPhase.WAITING)));
        return pods;
    }

    /**
     * Should we reconcile DC when task is done (ex: rebuild-stream)
     *
     * @return
     */
    public boolean reconcileDataCenterWhenDone() {
        return false;
    }

    public void updateMetrics(Task task, String taskTag, boolean succeed) {
        meterRegistry.counter(succeed ? "task.succeed" : "task.failed",
                "task", taskTag,
                "cluster", task.getSpec().getCluster(),
                "datacenter", task.getSpec().getDatacenter())
                .increment();
    }
}