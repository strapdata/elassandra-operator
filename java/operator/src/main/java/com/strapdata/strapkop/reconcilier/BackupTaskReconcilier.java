package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Singleton
@Infrastructure
public class BackupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(BackupTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    
    public BackupTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final SidecarClientFactory sidecarClientFactory,
                                 final MeterRegistry meterRegistry,
                                 final DataCenterController dataCenterController,
                                 final DataCenterCache dataCenterCache) {
        super(reconcilierObserver, "backup", k8sResourceUtils, meterRegistry, dataCenterController, dataCenterCache);
        this.sidecarClientFactory = sidecarClientFactory;
    }

    public BlockReason blockReason() {
        return BlockReason.BACKUP;
    }

    /**
     * Execute backup concurrently on all nodes
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws ApiException {
        // find the next pods to cleanup
        final List<String> pods = task.getStatus().getPods().entrySet().stream()
                .filter(e -> Objects.equals(e.getValue(), TaskPhase.WAITING))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        
        // if there is no more we are done
        if (pods.isEmpty()) {
            return Single.just(TaskPhase.SUCCEED);
        }

        // TODO: better backup with sstableloader and progress tracking
        // right now it just call the backup api on every nodes sidecar in parallel
        final BackupTaskSpec backupSpec = task.getSpec().getBackup();

        return Observable.fromIterable(pods)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod -> {

                    return sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                            .snapshot(backupSpec.getRepository(), backupSpec.getKeyspaces())
                            .map(backupResponse -> {
                                logger.debug("Received backupSpec response with status = {}", backupResponse.getStatus());
                                boolean success = backupResponse.getStatus().equalsIgnoreCase("success");
                                if (!success)
                                    task.getStatus().setLastMessage("Basckup task="+task.getMetadata().getName()+" on pod="+pod+" failed");
                                return new Tuple2<String, Boolean>(pod, success);
                            });
                })
                .toList()
                .flatMap(list -> finalizeTaskStatus(dc, task));
    }



    @Override
    public Completable initializePodMap(Task task, DataCenter dc) {
        return initializePodMapWithUnknownStatus(task, dc);
    }
}
