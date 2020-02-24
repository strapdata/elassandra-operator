package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
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
public final class CleanupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(CleanupTaskReconcilier.class);
    
    private final SidecarClientFactory sidecarClientFactory;
    
    public CleanupTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final SidecarClientFactory sidecarClientFactory,
                                  final MeterRegistry meterRegistry) {
        super(reconcilierObserver,"cleanup", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
    }

    public BlockReason blockReason() {
        return BlockReason.CLEANUP;
    }

    /**
     * Execute task on each pod and update the task status
     * @param taskWrapper
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws ApiException {
        final Task task = taskWrapper.getTask();

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
                .subscribeOn(Schedulers.computation())
                .flatMapSingle(pod -> sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod)).cleanup(task.getSpec().getCleanup().getKeyspace())
                        .andThen(updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.SUCCEED))
                        .onErrorResumeNext(throwable -> {
                            logger.error("Error while executing cleanup on pod={}", pod, throwable);
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return updateTaskPodStatus(dc, taskWrapper, TaskPhase.RUNNING, pod, TaskPhase.FAILED, throwable.getMessage());
                        })
                        .toSingleDefault(pod))
                .toList()
                .flatMap(list -> finalizeTaskStatus(dc, taskWrapper));
    }
}
