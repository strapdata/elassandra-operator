package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.backup.BackupArguments;
import com.strapdata.strapkop.model.backup.CloudStorageSecret;
import com.strapdata.strapkop.model.backup.CommonBackupArguments;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
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
import java.nio.file.Paths;
import java.util.ArrayList;
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
                                 final DataCenterUpdateReconcilier dataCenterUpdateReconcilier,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final SidecarClientFactory sidecarClientFactory,
                                 final CustomObjectsApi customObjectsApi,
                                 final WorkQueues workQueue,
                                 final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "backup", k8sResourceUtils, meterRegistry, dataCenterUpdateReconcilier);
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
        final CloudStorageSecret cloudSecret = k8sResourceUtils.readAndValidateStorageSecret(task.getMetadata().getNamespace(), backupSpec.getSecretRef(), backupSpec.getProvider());

        return Observable.fromIterable(pods)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod -> {
                    final BackupArguments backupArguments = generateBackupArguments(
                            task.getMetadata().getName(),
                            backupSpec,
                            OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()),
                            pod,
                            cloudSecret);

                    return sidecarClientFactory.clientForPod(ElassandraPod.fromName(dc, pod))
                            .backup(backupArguments)
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


    public static BackupArguments generateBackupArguments(final String tag, BackupTaskSpec backupSpec, final String datacenter,
                                                          final String pod, final CloudStorageSecret cloudCredentials) {
        BackupArguments backupArguments = new BackupArguments();
        backupArguments.cassandraConfigDirectory = Paths.get("/etc/cassandra/");
        backupArguments.cassandraDirectory = Paths.get("/var/lib/cassandra/");
        backupArguments.sharedContainerPath = Paths.get("/tmp"); // elassandra can't run as root
        backupArguments.snapshotTag = tag;
        backupArguments.storageProvider = backupSpec.getProvider();
        backupArguments.backupBucket = backupSpec.getBucket();
        backupArguments.keyspaceRegex = backupSpec.getKeyspaceRegex();
        if (backupSpec.getKeyspaces() != null) {
            backupArguments.keyspaces = new ArrayList<>(backupSpec.getKeyspaces());
        }
        backupArguments.offlineSnapshot = false;
        backupArguments.account = "";
        backupArguments.secret = "";
        backupArguments.clusterId = datacenter;
        backupArguments.backupId = pod;
        backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
        backupArguments.cloudCredentials = cloudCredentials;
        return backupArguments;
    }

    @Override
    public Completable initializePodMap(Task task, DataCenter dc) {
        for (Map.Entry<String, ElassandraNodeStatus> entry : dc.getStatus().getElassandraNodeStatuses().entrySet()) {
            if (!entry.getValue().equals(ElassandraNodeStatus.UNKNOWN)) {
                // only add reachable nodes (usually UNKNWON is used for unreachable or non bootstrapped node)
                task.getStatus().getPods().put(entry.getKey(), TaskPhase.WAITING);
            }
        }
        return Completable.complete();
    }
}
