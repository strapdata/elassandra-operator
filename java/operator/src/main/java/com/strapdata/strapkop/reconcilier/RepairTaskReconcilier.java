package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.vavr.Tuple2;
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

    private final SidecarClientFactory sidecarClientFactory;

    public RepairTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final SidecarClientFactory sidecarClientFactory,
                                 final MeterRegistry meterRegistry) {
        super(reconcilierObserver,"repair", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
    }
    
    @Override
    protected Single<TaskPhase> doTask(Task task, DataCenter dc) throws ApiException {

        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (pods.isEmpty()) {
            return Single.just(TaskPhase.SUCCEED);
        }

        // do clean up on each pod with 10 sec interval
        // TODO: maybe we should try to caught outer exception (even if we already catch inside doOnNext)
        return Observable.zip(Observable.fromIterable(pods), Observable.interval(10, TimeUnit.SECONDS), (pod, timer) -> pod)
                .flatMapSingle(pod -> {
                    TaskPhase podPhase = TaskPhase.SUCCEED;
                    try {
                        final Throwable t = sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod)).repairPrimaryRange(task.getSpec().getRepair().getKeyspace()).blockingGet();
                        if (t != null) throw t;
                    } catch (Throwable throwable) {
                        logger.error("Error while executing repair on {}", pod, throwable);
                        task.getStatus().setLastMessage(throwable.getMessage());
                    }
                    task.getStatus().getPods().put(pod, podPhase);
                    return updateTaskStatus(dc, task, TaskPhase.RUNNING).toSingleDefault(new Tuple2<>(pod, podPhase));
                })
                .toList()
                .map(list -> {
                    // finally compute the task phase
                    TaskPhase taskPhase = TaskPhase.SUCCEED;
                    for(Tuple2<String, TaskPhase> t : list) {
                        if (t._2.equals(TaskPhase.FAILED))
                            taskPhase = TaskPhase.FAILED;
                    }
                    return taskPhase;
                });
    }
}
