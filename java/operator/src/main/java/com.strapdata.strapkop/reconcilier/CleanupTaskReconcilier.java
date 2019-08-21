package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Observable;
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
    
    public CleanupTaskReconcilier(K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory) {
        super("cleanup", k8sResourceUtils);
        this.sidecarClientFactory = sidecarClientFactory;
    }
    
    @Override
    protected void doTask(Task task, DataCenter dc) throws ApiException {
        
        // if it's the first time, initialize the map of pods status
        if (task.getStatus().getPods() == null) {
            task.getStatus().setPhase(TaskPhase.STARTED);
            initializePodMap(task, dc);
        }
        
        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // if there is no more we are done
        if (pods.isEmpty()) {
            task.getStatus().setPhase(TaskPhase.SUCCEED);
            k8sResourceUtils.updateTaskStatus(task);
            ensureUnlockDc(task, dc);
            return ;
        }
        
        // do clean up on each pod with 10 sec interval
        // TODO: maybe we should try to caught outer exception (even if we already catch inside doOnNext)
        Observable.zip(
                Observable.fromIterable(pods),
                Observable.interval(10, TimeUnit.SECONDS),
                (pod, timer) -> pod)
                .doOnNext(pod -> {
                    try {
                        final Throwable t = sidecarClientFactory.clientForPod(new ElassandraPod(dc, pod)).cleanup().blockingGet();
                        if (t != null) throw t;
                        task.getStatus().getPods().put(pod, TaskPhase.SUCCEED);
                    } catch (Throwable throwable) {
                        logger.error("error while executing cleanup on {}", pod, throwable);
                        task.getStatus().getPods().put(pod, TaskPhase.FAILED);
                        task.getStatus().setLastErrorMessage(throwable.getMessage());
                        task.getStatus().setPhase(TaskPhase.FAILED);
                    }
                })
                .toList().blockingGet();

        k8sResourceUtils.updateTaskStatus(task);
    }
}
