package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public final class RepairTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RepairTaskReconcilier.class);

    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    public RepairTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final JmxmpElassandraProxy jmxmpElassandraProxy,
                                 final MeterRegistry meterRegistry,
                                 final DataCenterController dataCenterController) {
        super(reconcilierObserver,"repair", k8sResourceUtils, meterRegistry, dataCenterController);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    public BlockReason blockReason() {
        return BlockReason.REPAIR;
    }
    
    @Override
    protected Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws ApiException {
        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (pods.isEmpty()) {
            return Single.just(TaskPhase.SUCCEED);
        }

        return Observable.zip(Observable.fromIterable(pods), Observable.interval(10, TimeUnit.SECONDS), (pod, timer) -> pod)
                .subscribeOn(Schedulers.computation())
                .flatMapSingle(pod -> jmxmpElassandraProxy.repair(ElassandraPod.fromName(dc, pod), task.getSpec().getRepair().getKeyspace())
                        .andThen(updateTaskPodStatus(dc, task, TaskPhase.RUNNING, pod, TaskPhase.SUCCEED))
                        .onErrorResumeNext(throwable -> {
                            logger.error("Error while executing repair on pod={}", pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return updateTaskPodStatus(dc, task, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                        })
                        .toSingleDefault(pod))
                .toList()
                .flatMap(list -> finalizeTaskStatus(dc, task));
    }
}
