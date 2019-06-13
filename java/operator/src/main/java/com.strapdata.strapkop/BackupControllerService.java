package com.strapdata.strapkop;

import com.google.common.base.Strings;
import com.google.common.collect.Streams;
import com.strapdata.model.Key;
import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.backup.CommonBackupArguments;
import com.strapdata.model.backup.RestoreArguments;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.backup.Backup;
import com.strapdata.model.k8s.backup.BackupSpec;
import com.strapdata.model.k8s.backup.BackupStatus;
import com.strapdata.model.sidecar.BackupResponse;
import com.strapdata.strapkop.k8s.K8sModule;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import com.strapdata.strapkop.watch.BackupWatchService;
import com.strapdata.strapkop.watch.WatchEvent;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1Pod;
import io.micronaut.context.annotation.Context;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Listen for Backup events from the BackupWatchService and call sidecar to execute backup.
 */
@Context
public class BackupControllerService {
    private static final Logger logger = LoggerFactory.getLogger(BackupControllerService.class);
    private static final Key<Backup> POISON = new Key<Backup>(null, null);

    private final K8sResourceUtils k8sResourceUtils;
    private final HashMap<Key<Backup>, Backup> backupCache = new HashMap<>();
    private final CustomObjectsApi customObjectsApi;
    private final SidecarClientFactory sidecarClientFactory;

    //private final BlockingQueue<Key<Backup>> backupQueue = new LinkedBlockingQueue<>();
    private final BackupWatchService backupWatchService;

    @Inject
    public BackupControllerService(final K8sModule k8sModule,
                                   final K8sResourceUtils k8sResourceUtils,
                                   final BackupWatchService backupWatchService,
                                   final SidecarClientFactory sidecarClientFactory) {
        logger.info("initializing backup service");

        this.k8sResourceUtils = k8sResourceUtils;
        this.customObjectsApi = k8sModule.provideCustomObjectsApi();
        this.sidecarClientFactory = sidecarClientFactory;
        this.backupWatchService = backupWatchService;
        backupWatchService.getSubject().subscribe(event -> {
            logger.info("Received BackupWatchEvent {}.", event);
            if(event.t.getStatus() == null ||
                Strings.isNullOrEmpty(event.t.getStatus().getProgress()) ||
                !event.t.getStatus().getProgress().equals("PROCESSED")) {
                try {
                    logger.debug("Reconciling Backup.");
                    createOrReplaceBackup(event.t).map(success -> {
                        event.t.setStatus(new BackupStatus().setProgress(success ? "PROCESSED" : "FAILED" ));
                        logger.info("Backcup name={} namespace={} success={}",
                            event.t.getMetadata().getName(), event.t.getMetadata().getNamespace(), success);

                        customObjectsApi.patchNamespacedCustomObject("stable.strapdata.com", "v1",
                            event.t.getMetadata().getNamespace(), "elassandra-backups",
                            event.t.getMetadata().getName(), event.t);
                        return success;
                    });
                } catch (final Exception e) {
                    logger.warn("Failed to reconcile Backup. This will be an exception in the future.", e);
                }
            } else if (event instanceof WatchEvent.Deleted) {
                deleteBackup(event.t);
            } else {
                logger.debug("Skipping already processed backup {}", event.t.getMetadata().getName());
            }
        });
    }


    private Single<BackupResponse> callBackupApi(final V1Pod pod, Backup backup)  {
       try {
           BackupArguments backupArguments = generateBackupArguments(pod.getStatus().getPodIP(),
               7199,
               backup.getMetadata().getName(),
               StorageProvider.valueOf(backup.getSpec().getBackupType()),
               backup.getSpec().getTarget(),
               pod.getMetadata().getLabels().get(OperatorLabels.DATACENTER));

           backupArguments.backupId = pod.getSpec().getHostname();
           backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
           return sidecarClientFactory.clientForPod(pod).backup(backupArguments);
        } catch (MalformedURLException | UnknownHostException e) {
           return Single.error(e);
        }
    }


    /**
     * Update the k8s backup status.
     * @param backup
     * @throws ApiException
     */
    private Single<Boolean> createOrReplaceBackup(final Backup backup) throws ApiException {
        final BackupSpec backupSpec = backup.getSpec();

        final String dataCenterPodsLabelSelector = backupSpec.getSelector().getMatchLabels().entrySet().stream()
                .map(x -> x.getKey() + "=" + x.getValue())
                .collect(Collectors.joining(","));
        
        final Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(backup.getMetadata().getNamespace(), null, dataCenterPodsLabelSelector);
        return Single.zip(Streams.stream(pods)
                .map(pod -> callBackupApi(pod, backup))
                .collect(Collectors.toList()),
            responses -> {
                boolean success = true;
                for(int i=0; i< responses.length; i++) {
                    if (responses[i] instanceof BackupResponse && !((BackupResponse)responses[i]).getStatus().equalsIgnoreCase("FAILED")) {
                        success = false;
                        break;
                    }
                }
                return success;
            });
    }

    private void deleteBackup(Backup backup) {
        logger.warn("Deleting backups is not implemented.");
    }


    public static BackupArguments generateBackupArguments(final String ip, final int port, final String tag, final StorageProvider provider, final String target, final String cluster) {
        BackupArguments backupArguments = new BackupArguments();
        backupArguments.setJmxServiceURLFromIp(ip, port);
        backupArguments.cassandraConfigDirectory = Paths.get("/etc/cassandra/");
        backupArguments.cassandraDirectory = Paths.get("/var/lib/cassandra/");
        backupArguments.sharedContainerPath = Paths.get("/tmp"); // elassandra can't ran as root
        backupArguments.snapshotTag = tag;
        backupArguments.storageProvider = provider;
        backupArguments.backupBucket = target;
        backupArguments.offlineSnapshot = false;
        backupArguments.account = "";
        backupArguments.secret = "";
        backupArguments.clusterId = cluster;
        return backupArguments;
    }

    public static RestoreArguments generateRestoreArguments() {
        RestoreArguments restoreArguments = new RestoreArguments();
        return restoreArguments;
    }
}
