package com.strapdata.strapkop;

import io.micronaut.context.annotation.Context;

/**
 * Listen for Backup events from the BackupWatchService and call sidecar to execute backup.
 */
//@Context
public class BackupControllerService {
//    private static final Logger logger = LoggerFactory.getLogger(BackupControllerService.class);
//    private static final Key POISON = new Key(null, null);
//
//    private final K8sResourceUtils k8sResourceUtils;
//    private final HashMap<Key, Backup> backupCache = new HashMap<>();
//    private final CustomObjectsApi customObjectsApi;
//    private final SidecarClientFactory sidecarClientFactory;
//
//    //private final BlockingQueue<Key<Backup>> backupQueue = new LinkedBlockingQueue<>();
//    private final BackupWatchService backupWatchService;
//
//    @Inject
//    public BackupControllerService(final K8sModule k8sModule,
//                                   final K8sResourceUtils k8sResourceUtils,
//                                   final BackupWatchService backupWatchService,
//                                   final SidecarClientFactory sidecarClientFactory) {
//        logger.info("initializing backup service");
//
//        this.k8sResourceUtils = k8sResourceUtils;
//        this.customObjectsApi = k8sModule.provideCustomObjectsApi();
//        this.sidecarClientFactory = sidecarClientFactory;
//        this.backupWatchService = backupWatchService;
//        backupWatchService.getSubject().subscribe(event -> {
//            logger.info("Received BackupWatchEvent {}.", event);
//            if(event.t.getStatus() == null ||
//                Strings.isNullOrEmpty(event.t.getStatus().getProgress()) /* do not retry backup ||
//                !event.t.getStatus().getProgress().equals("PROCESSED") */) {
//                try {
//                    logger.debug("Reconciling Backup.");
//                    callBackupApiAllPods(event.t).onErrorReturnItem(false).subscribe(success -> {
//                        event.t.setStatus(new BackupStatus().setProgress(success ? "PROCESSED" : "FAILED" ));
//                        logger.info("Backup name={} namespace={} success={}",
//                            event.t.getMetadata().getName(), event.t.getMetadata().getNamespace(), success);
//
//                        customObjectsApi.patchNamespacedCustomObject("stable.strapdata.com", "v1",
//                            event.t.getMetadata().getNamespace(), "elassandra-backups",
//                            event.t.getMetadata().getName(), event.t);
//                    });
//                } catch (final Exception e) {
//                    logger.warn("Failed to reconcile Backup. This will be an exception in the future.", e);
//                }
//            } else if (event instanceof WatchEvent.Deleted) {
//                deleteBackup(event.t);
//            } else {
//                logger.debug("Skipping already processed backup {}", event.t.getMetadata().getName());
//            }
//        });
//    }
//
//
//    private Single<Boolean> callBackupApi(final V1Pod pod, Backup backup)  {
//       try {
//           BackupArguments backupArguments = generateBackupArguments(pod.getStatus().getPodIP(),
//               7199,
//               backup.getMetadata().getName(),
//               StorageProvider.valueOf(backup.getSpec().getBackupType()),
//               backup.getSpec().getTarget(),
//               pod.getMetadata().getLabels().get(OperatorLabels.DATACENTER));
//
//           backupArguments.backupId = pod.getSpec().getHostname();
//           backupArguments.speed = CommonBackupArguments.Speed.LUDICROUS;
//           return sidecarClientFactory.clientForPod(pod)
//                   .backup(backupArguments)
//                   .doOnSuccess(backupResponse -> logger.debug("received backup response with status = {}", backupResponse.getStatus()))
//                   .map(backupResponse -> backupResponse.getStatus().equalsIgnoreCase("success"))
//                   .onErrorReturn(throwable -> {
//                       logger.warn("error occured from sidecar backup");
//                       throwable.printStackTrace();
//                       return false;
//                   });
//        } catch (MalformedURLException | UnknownHostException e) {
//           return Single.error(e);
//        }
//    }
//
//
//    /**
//     * Update the k8s backup status.
//     *
//     * TODO: this method name is insane
//     * @param backup
//     * @throws ApiException
//     */
//    private Single<Boolean> callBackupApiAllPods(final Backup backup) throws ApiException {
//        final BackupSpec backupSpec = backup.getSpec();
//
//        final String dataCenterPodsLabelSelector = backupSpec.getSelector().getMatchLabels().entrySet().stream()
//                .map(x -> x.getKey() + "=" + x.getValue())
//                .collect(Collectors.joining(","));
//
//        final Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(backup.getMetadata().getNamespace(), null, dataCenterPodsLabelSelector);
//        return Single.zip(
//                Streams.stream(pods).map(pod -> callBackupApi(pod, backup)).collect(Collectors.toList()),
//                successes -> Arrays.stream((Boolean[])successes).allMatch(s -> s)
//        );
//    }
//
//    private void deleteBackup(Backup backup) {
//        logger.warn("Deleting backups is not implemented.");
//    }
//
//
//    public static BackupArguments generateBackupArguments(final String ip, final int port, final String tag, final StorageProvider provider, final String target, final String cluster) {
//        BackupArguments backupArguments = new BackupArguments();
//        backupArguments.setJmxServiceURLFromIp(ip, port);
//        backupArguments.cassandraConfigDirectory = Paths.get("/etc/cassandra/");
//        backupArguments.cassandraDirectory = Paths.get("/var/lib/cassandra/");
//        backupArguments.sharedContainerPath = Paths.get("/tmp"); // elassandra can't ran as root
//        backupArguments.snapshotTag = tag;
//        backupArguments.storageProvider = provider;
//        backupArguments.backupBucket = target;
//        backupArguments.offlineSnapshot = false;
//        backupArguments.account = "";
//        backupArguments.secret = "";
//        backupArguments.clusterId = cluster;
//        return backupArguments;
//    }
//
//    public static RestoreArguments generateRestoreArguments() {
//        RestoreArguments restoreArguments = new RestoreArguments();
//        return restoreArguments;
//    }
}
