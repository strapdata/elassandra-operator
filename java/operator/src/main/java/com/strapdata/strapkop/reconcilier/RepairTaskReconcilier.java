package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.RepairTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Singleton
@Infrastructure
public final class RepairTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RepairTaskReconcilier.class);

    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    public RepairTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final OperatorConfig operatorConfig,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final JmxmpElassandraProxy jmxmpElassandraProxy,
                                 final MeterRegistry meterRegistry,
                                 final DataCenterController dataCenterController,
                                 final DataCenterCache dataCenterCache,
                                 final DataCenterStatusCache dataCenterStatusCache,
                                 ExecutorFactory executorFactory,
                                 @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, dataCenterStatusCache, executorFactory, userExecutorConfiguration);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    @Override
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws ApiException {
        final RepairTaskSpec repairTaskSpec = task.getSpec().getRepair();
        return Observable.zip(Observable.fromIterable(pods), Observable.interval(repairTaskSpec.getWaitIntervalInSec(), TimeUnit.SECONDS), (pod, timer) -> pod)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod -> jmxmpElassandraProxy.repair(ElassandraPod.fromV1Pod(pod), task.getSpec().getRepair().getKeyspace())
                        .toSingleDefault(pod)
                        .map(p -> {
                            // update pod status in memory (no etcd update)
                            task.getStatus().getPods().put(p.getMetadata().getName(), TaskPhase.SUCCEED);
                            logger.debug("datacenter={} task={} repair pod={} done", dc.id(), task.id(), p.getMetadata().getName());
                            return p;
                        })
                        .ignoreElement()
                        .onErrorResumeNext(throwable -> {
                            logger.error("Error while executing repair on pod={}", pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            task.getStatus().getPods().put(pod.getMetadata().getName(), TaskPhase.FAILED);
                            return Completable.complete();
                        })
                        .toSingleDefault(pod))
                .toList()
                .flatMapCompletable(list -> finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "repair"));
    }

    // repair PR on all available nodes
    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }
}
