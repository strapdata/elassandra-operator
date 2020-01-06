package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.strapdata.backup.manifest.GlobalManifest;
import com.strapdata.backup.manifest.ManifestReader;
import com.strapdata.cassandra.k8s.ElassandraOperatorSeedProviderAndNotifier;
import com.strapdata.model.backup.CloudStorageSecret;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cql.*;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import com.strapdata.strapkop.utils.CloudStorageSecretsKeys;
import com.strapdata.strapkop.utils.DnsUpdateSecretsKeys;
import com.strapdata.strapkop.utils.ManifestReaderFactory;
import com.strapdata.strapkop.utils.RestorePointCache;
import com.strapdata.strapkop.utils.RestorePointCache.RestorePoint;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.util.StringUtils;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import io.reactivex.schedulers.Schedulers;
import io.vavr.collection.Stream;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The NodePort service has a DNS name and redirect to STS seed pods
 * see https://stackoverflow.com/questions/46456239/how-to-expose-a-headless-service-for-a-statefulset-externally-in-kubernetes
 */
@Prototype
public class DataCenterUpdateAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterUpdateAction.class);

    private static final long MB = 1024 * 1024;
    private static final long GB = MB * 1024;

    public static final String OPERATOR_KEYSTORE_MOUNT_PATH = "/tmp/operator-keystore";
    public static final String OPERATOR_KEYSTORE = "keystore.p12";
    public static final String OPERATOR_KEYPASS = "changeit";


    public static final String KEY_JMX_PASSWORD = "cassandra.jmx_password";
    public static final String KEY_SHARED_SECRET = "shared-secret.yaml";
    public static final String KEY_REAPER_PASSWORD = "cassandra.reaper_password";

    public static final long CASSANDRA_USER_ID = 999L;
    public static final long CASSANDRA_GROUP_ID = 999L;

    private final ApplicationContext context;
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final AuthorityManager authorityManager;

    private final com.strapdata.model.k8s.cassandra.DataCenter dataCenter;
    private final V1ObjectMeta dataCenterMetadata;
    private final DataCenterSpec dataCenterSpec;
    private final DataCenterStatus dataCenterStatus;
    private final Map<String, String> dataCenterLabels;
    private final SidecarClientFactory sidecarClientFactory;

    private final CqlRoleManager cqlRoleManager;
    private final CqlLicenseManager cqlLicenseManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    private final OperatorConfig operatorConfig;

    private final ManifestReaderFactory manifestReaderFactory;

    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    public final Builder builder = new Builder();

    public DataCenterUpdateAction(final ApplicationContext context,
                                  final CoreV1Api coreApi,
                                  final AppsV1Api appsApi,
                                  final CustomObjectsApi customObjectsApi,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final AuthorityManager authorityManager,
                                  final CqlRoleManager cqlRoleManager,
                                  final CqlKeyspaceManager cqlKeyspaceManager,
                                  final ElassandraNodeStatusCache elassandraNodeStatusCache,
                                  final SidecarClientFactory sidecarClientFactory,
                                  @Parameter("dataCenter") com.strapdata.model.k8s.cassandra.DataCenter dataCenter,
                                  final CqlLicenseManager cqlLicenseManager,
                                  final ManifestReaderFactory factory,
                                  final OperatorConfig operatorConfig) {
        this.context = context;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.operatorConfig = operatorConfig;

        this.dataCenter = dataCenter;
        this.dataCenterMetadata = dataCenter.getMetadata();
        this.dataCenterSpec = dataCenter.getSpec();

        this.cqlRoleManager = cqlRoleManager;
        this.cqlLicenseManager = cqlLicenseManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.sidecarClientFactory = sidecarClientFactory;
        if (dataCenter.getStatus() == null) {
            dataCenter.setStatus(new DataCenterStatus());
        }
        this.dataCenterStatus = this.dataCenter.getStatus();

        // normalize Enterprise object
        if (this.dataCenterSpec.getEnterprise() == null) {
            this.dataCenterSpec.setEnterprise(new Enterprise());
        } else if (!this.dataCenterSpec.getEnterprise().getEnabled()) {
            this.dataCenterSpec.setEnterprise(new Enterprise());
        }

        this.dataCenterLabels = OperatorLabels.datacenter(dataCenter);

        this.manifestReaderFactory = factory;
    }

    /**
     * Fetch existing statefulsets from k8s api and sort then by zone name
     *
     * @return a map of zone name -> statefulset
     * @throws ApiException      if there is an error with k8s api
     * @throws StrapkopException if the statefulset has no RACK label or if two statefulsets has the same zone label
     */
    public Single<TreeMap<String, V1StatefulSet>> fetchExistingStatefulSetsByZone() throws ApiException, StrapkopException {
        return Single.fromCallable(new Callable<TreeMap<String, V1StatefulSet>>() {
            @Override
            public TreeMap<String, V1StatefulSet> call() throws Exception {
                final Iterable<V1StatefulSet> statefulSetsIterable = k8sResourceUtils.listNamespacedStatefulSets(
                        dataCenterMetadata.getNamespace(), null,
                        OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter)));

                final TreeMap<String, V1StatefulSet> result = new TreeMap<>();

                for (V1StatefulSet sts : statefulSetsIterable) {
                    final String zone = sts.getMetadata().getLabels().get(OperatorLabels.RACK);

                    if (zone == null) {
                        throw new StrapkopException(String.format("statefulset %s has no RACK label", sts.getMetadata().getName()));
                    }
                    if (result.containsKey(zone)) {
                        throw new StrapkopException(String.format("two statefulsets in the same zone=%s dc=%s", zone, dataCenter.getMetadata().getName()));
                    }
                    result.put(zone, sts);
                }

                return result;
            }
        });
    }

    /**
     *
     * @return
     * @throws Exception
     */
    public Completable switchDataCenterUpdateOff(ElassandraPod unscheduledPod) throws Exception {
        return fetchExistingStatefulSetsByZone()
                .flatMapCompletable(existingStsMap -> {
                    Zones zones = new Zones(this.coreApi, existingStsMap);

                    // 1.lookup for evolving rack
                    final Map<String, RackStatus> rackStatusByName = new HashMap<>();
                    RackStatus movingRack = null;
                    for (RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        rackStatusByName.put(rackStatus.getName(), rackStatus);
                        if (!rackStatus.getPhase().equals(RackPhase.RUNNING)) {
                            if (movingRack != null) {
                                logger.error("Found more than one moving rack=[{},{}]", movingRack.getName(), rackStatus.getName());
                            }

                            movingRack = rackStatus;

                            if (movingRack.getPhase().equals(RackPhase.UPDATING) && movingRack.getName().equals(unscheduledPod.getRack())){
                                logger.debug("Set rack phase to '{}' for rack '{}'", RackPhase.SCHEDULING_PENDING, movingRack.getName());
                                movingRack.setPhase(RackPhase.SCHEDULING_PENDING);
                            }
                        }
                    }

                    updateDatacenterStatus(DataCenterPhase.ERROR, zones, rackStatusByName, Optional.of("Unable to schedule Pod " + unscheduledPod.getName()));
                    return Completable.complete();
                });
    }

    public Completable freePodResource(ElassandraPod deletedPod) throws Exception {
        return Completable.fromAction(() -> {
            // delete PVC only if the node was decommissioned to avoid deleting PVC in unexpectedly killed pod during a DC ScaleDown
            ElassandraNodeStatus podStatus = Optional.ofNullable(elassandraNodeStatusCache.get(deletedPod)).orElse(ElassandraNodeStatus.UNKNOWN);
            if (ElassandraNodeStatus.DECOMMISSIONED.equals(podStatus)) {
                switch (dataCenterSpec.getDecommissionPolicy()) {
                    case KEEP_PVC:
                        break;
                    case BACKUP_AND_DELETE_PVC:
                        // TODO
                        break;
                    case DELETE_PVC:
                        List<V1PersistentVolumeClaim> pvcsToDelete = Stream.ofAll(k8sResourceUtils.listNamespacedPodsPersitentVolumeClaims(
                                dataCenterMetadata.getNamespace(),
                                null,
                                OperatorLabels.toSelector(OperatorLabels.rack(dataCenter, deletedPod.getRack()))))
                                .filter(pvc -> {
                                    boolean match = pvc.getMetadata().getName().endsWith(deletedPod.getName());
                                    logger.info("PVC {} will be deleted due to pod {} deletion", pvc.getMetadata().getName(), deletedPod.getName());
                                    return match;
                                }).collect(Collectors.toList());

                        if (pvcsToDelete.size() > 1) {
                            logger.warn("Too many PVC found for deletion, cancel it ! (List of PVC = {})", pvcsToDelete);
                        } else {
                            for (V1PersistentVolumeClaim pvc : pvcsToDelete) {
                                k8sResourceUtils.deletePersistentVolumeClaim(pvc).subscribe();
                            }
                        }
                }
            } else {
                logger.info("Resources can't be delete for pod {} in status {}", deletedPod.getName(), podStatus);
            }
        });
    }

    public Completable rollbackDataCenter(Optional<RestorePoint> restorePoint) throws Exception {
        return fetchExistingStatefulSetsByZone()
                .flatMapCompletable(existingStsMap -> {
                    Zones zones = new Zones(this.coreApi, existingStsMap);

                    // 1.lookup for unscheduled rack
                    final Map<String, RackStatus> rackStatusByName = new HashMap<>();
                    boolean foundUnscheduledRack = false;
                    for (RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        rackStatusByName.put(rackStatus.getName(), rackStatus);
                        if (rackStatus.getPhase().equals(RackPhase.SCHEDULING_PENDING)) {
                            foundUnscheduledRack = true;
                        }
                    }

                    if (!foundUnscheduledRack) {
                        logger.warn("Rollback requested for '{}' but there are no rack in Phase '{}'. Rollback is cancelled !", dataCenterMetadata.getName(), RackPhase.SCHEDULING_PENDING);
                        return Completable.complete();
                    }

                    return Completable.fromSingle(
                            Single.fromCallable( () -> {

                                if (restorePoint.isPresent()) {

                                    final RestorePoint previousStableState =restorePoint.get();
                                    logger.info("Try to restore DataCenter configuration with fingerprint '{}' and userConfigMap '{}'", previousStableState.getSpec().fingerprint(), previousStableState.getUserConfigMap());

                                    if (previousStableState.getUserConfigMap() != null) {
                                        logger.trace("ROLLBACK user ConfigMap : {}", dataCenterSpec.getUserConfigMapVolumeSource().getName());
                                        V1ConfigMap previousUserConfig = k8sResourceUtils.readNamespacedConfigMap(dataCenterMetadata.getNamespace(), previousStableState.getUserConfigMap()).blockingGet();
                                        // override the current ConfigMap with the Previous one
                                        // force the ConfigMap name with the previous one without fingerprint
                                        logger.trace("RESTORE user ConfigMap : {}", previousStableState.getSpec().getUserConfigMapVolumeSource().getName());
                                        previousUserConfig.getMetadata().setName(previousStableState.getSpec().getUserConfigMapVolumeSource().getName());
                                        k8sResourceUtils.createOrReplaceNamespacedConfigMap(previousUserConfig).blockingGet();
                                    } else {
                                        logger.info("No user ConfigMap to restore");
                                    }

                                    // restore previous configuration
                                    logger.trace("ROLLBACK DataCenter Spec : {}", dataCenterSpec);
                                    logger.trace("RESTORE DataCenter Spec : {}",previousStableState.getSpec());
                                    dataCenter.setSpec(previousStableState.getSpec());

                                    return k8sResourceUtils.updateDataCenter(dataCenter)
                                            .map((updatedDatacenter) -> {
                                                // update dc status
                                                updateDatacenterStatus(updatedDatacenter.getStatus(), DataCenterPhase.ROLLING_BACK, zones, rackStatusByName, Optional.of(""));
                                                return k8sResourceUtils.updateDataCenterStatus(updatedDatacenter).blockingGet();
                                            }).blockingGet();
                                } else {
                                    // update dc status
                                    updateDatacenterStatus(dataCenterStatus, DataCenterPhase.ROLLING_BACK, zones, rackStatusByName, Optional.of(""));
                                    return k8sResourceUtils.updateDataCenterStatus(dataCenter).blockingGet();
                                }
                            }));
                });
    }

    /**
     * Reconcile ONE rack at a time to reach the desired configuration.
     * 1.check for moving rack until operation is completed.
     * 2.check for next rack to update.
     * => Datacenter phase is the phase of the moving rack or RUNNING when there is nothing to do.
     * => rack operation = { UPDATE, SCALE_UP, SCALE_DOWN, EXECUTE_TASK, FAILED }
     * => rack state = { NORMAL, UPDATING, SCALING_IP, SCALING_DOWN, EXECUTING_TASK }
     *
     * @return
     * @throws Exception
     */
    public Completable reconcileDataCenter() throws Exception {
        logger.info("Reconciling DataCenter {} in namespace={}, phase={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace(), dataCenterStatus.getPhase());

        if (dataCenterSpec.getReplicas() <= 0) {
            throw new StrapkopException(String.format("dc=%s has an invalid number of replicas", dataCenterMetadata.getName()));
        }

        return Single.zip(
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceNodes()),
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceElasticsearch()),
                k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceExternalNodes()),
                (s1, s2, s3) -> builder.clusterObjectMeta(OperatorNames.clusterSecret(dataCenter))
        )
                .flatMap(clusterSecretMeta -> {
                    // create cluster secret if not exists
                    final Map<String, String> passwords = new HashMap<>();
                    passwords.put(CqlRole.KEY_CASSANDRA_PASSWORD, UUID.randomUUID().toString());
                    passwords.put(CqlRole.KEY_ELASSANDRA_OPERATOR_PASSWORD, UUID.randomUUID().toString());
                    passwords.put(CqlRole.KEY_ADMIN_PASSWORD, UUID.randomUUID().toString());
                    passwords.put(KEY_JMX_PASSWORD, UUID.randomUUID().toString());
                    passwords.put(KEY_SHARED_SECRET, "aaa.shared_secret: " + UUID.randomUUID().toString());
                    passwords.put(KEY_REAPER_PASSWORD, UUID.randomUUID().toString());
                    return k8sResourceUtils.readOrCreateNamespacedSecret(clusterSecretMeta, () -> {
                                V1Secret secret = new V1Secret().metadata(clusterSecretMeta);
                                for(Map.Entry<String,String> entry : passwords.entrySet())
                                    secret.putStringDataItem(entry.getKey(), entry.getValue());
                                return secret;
                            });
                })
                .map(s -> {
                    Map<String, String> passwords = s.getData().entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> new String(e.getValue())));
                    if (context.getEnvironment().getActiveNames().contains("test")) {
                        logger.warn("secret passwords={}", passwords);
                    }
                    return passwords;
                })
                .flatMap(passwords -> {
                    // create rc file
                    return k8sResourceUtils.readOrCreateNamespacedSecret(builder.clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter)),
                            () -> builder.buildSecretRcFile("admin", passwords.get(CqlRole.KEY_ADMIN_PASSWORD)))
                            .map(s -> {
                                passwords.clear();
                                return passwords;
                            });
                })
                .flatMap(passwords -> {
                    // create SSL keystore if not exists
                    return !dataCenterSpec.getSsl() ?
                            Single.just(passwords) :
                            authorityManager.loadFromSecretSingle()
                                    .flatMap(x509CertificateAndPrivateKey -> {
                                        V1ObjectMeta keystoreMeta = builder.dataCenterObjectMeta(OperatorNames.keystoreSecret(dataCenter));
                                        return k8sResourceUtils.readOrCreateNamespacedSecret(keystoreMeta,
                                                () -> {
                                                    try {
                                                        return builder.buildSecretKeystore(x509CertificateAndPrivateKey);
                                                    } catch (GeneralSecurityException | IOException | OperatorCreationException e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                });
                                    }).map(s2 -> passwords);
                })
                .flatMap(s4 -> fetchExistingStatefulSetsByZone())
                .flatMapCompletable(existingStsMap -> {
                    Zones zones = new Zones(this.coreApi, existingStsMap);
                    final Map<String, RackStatus> rackStatusByName = new HashMap<>();

                    // 1.lookup for evolving rack
                    RackStatus movingRack = null;
                    for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                        rackStatusByName.put(rackStatus.getName(), rackStatus);
                        if (!(rackStatus.getPhase().equals(RackPhase.RUNNING) || rackStatus.getPhase().equals(RackPhase.PARKED))) {
                            if (movingRack != null)
                                logger.error("Found more than one moving rack=[{},{}]", movingRack.getName(), rackStatus.getName());
                            movingRack = rackStatus;
                        }
                    }

                    ElassandraPod failedPod = null;

                    if (movingRack != null) {
                        Zone movingZone = zones.zoneMap.get(movingRack.getName());
                        logger.debug("movingRack={} phase={} isReady={} isUpdating={} isScalingUp={} isScalingDown={} firstPodStatus={} lastPodStatus={}",
                                movingRack.getName(), movingRack.getPhase(), movingZone.isReady(), movingZone.isUpdating(), movingZone.isScalingUp(), movingZone.isScalingDown(),
                                elassandraNodeStatusCache.get(movingZone.firstPod(dataCenter)), elassandraNodeStatusCache.get(movingZone.lastPod(dataCenter)));
                        // check is operation is finished ?
                        switch(movingRack.getPhase()) {
                            case CREATING:
                                // first node NORMAL
                                if (movingZone.isReady() && elassandraNodeStatusCache.isNormal(movingZone.firstPod(dataCenter))) {
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    movingRack.setJoinedReplicas(1);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("First node NORMAL of rack={}", movingZone.name);
                                }

                                commitDataCenterSnapshot(zones);

                                break;
                            case UPDATING:
                                // rolling update done and first node NORMAL
                                if (!movingZone.isUpdating() && allReplicasRunnning(movingZone)) {
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("First node NORMAL after rolling UPDATE in rack={} size={}", movingZone.name, movingZone.size);
                                }

                                commitDataCenterSnapshot(zones);
                                break;
                            case SCALING_UP:
                                // scale up done and last node NORMAL
                                if (!movingZone.isScalingUp() && allReplicasRunnning(movingZone)) {
                                    movingRack.setJoinedReplicas(movingZone.size);// TODO [ELU] should be NON UNKNOWN node?
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("Last node NORMAL after SCALE_UP in rack={} size={}", movingZone.name, movingZone.size);
                                }

                                break;
                            case SCALING_DOWN:
                                // scale down
                                if (!movingZone.isScalingDown() && zones.totalCurrentReplicas() == dataCenterSpec.getReplicas()) {
                                    movingRack.setJoinedReplicas(movingZone.size);
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("SCALE_DOWN done in rack={} size={}", movingZone.name, movingZone.size);
                                } else {
                                    // have to update the STS here to avoid multiple moving rack...
                                    Optional<ElassandraPod> decommissionedPod = movingZone.pods(dataCenter).stream()
                                            .filter((pod) -> elassandraNodeStatusCache.get(pod).equals(ElassandraNodeStatus.DECOMMISSIONED))
                                            .findFirst();

                                    if (decommissionedPod.isPresent()) {
                                        V1StatefulSet sts = movingZone.getSts().get();
                                        // decrease the number of replicas only once... by testing the STS status
                                        if (sts.getStatus().getUpdatedReplicas() == sts.getStatus().getCurrentReplicas()
                                        && sts.getStatus().getUpdatedReplicas() == sts.getStatus().getReadyReplicas()) {

                                            sts.getSpec().setReplicas(sts.getSpec().getReplicas() - 1);
                                            logger.info("Scaling down sts={} to {}, removing pod={}",
                                                    sts.getMetadata().getName(), sts.getSpec().getReplicas(), decommissionedPod.get());

                                            rackStatusByName.get(movingZone.name).setPhase(RackPhase.SCALING_DOWN);
                                            updateDatacenterStatus(DataCenterPhase.SCALING_DOWN, zones, rackStatusByName);
                                            // scale down sts
                                            logger.debug("SCALE_DOWN started in rack={} size={}, removing pod={} status={}",
                                                    movingZone.name, movingZone.size, decommissionedPod.get(), dataCenterStatus.getElassandraNodeStatuses().get(decommissionedPod.get()));
                                            return replaceNamespacedStatefulSet(sts);
                                        }
                                    }
                                }

                                break;
                            case SCHEDULING_PENDING:
                                logger.warn("rack='{}' in phase SCHEDULING_ERROR", movingZone.name);
                                if (movingZone.isReady() && allReplicasRunnning(movingZone)) {
                                    logger.info("All replicas are running in rack={}, Scheduling issue was resolved, Datacenter is back to stable state",  movingZone.name);
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);

                                    commitDataCenterSnapshot(zones);

                                } else if (dataCenterStatus.getPhase().equals(DataCenterPhase.ROLLING_BACK)){
                                    V1StatefulSet v1StatefulSet = movingZone.getSts().get();
                                    String rack = v1StatefulSet.getSpec().getTemplate().getMetadata().getLabels().get("rack");
                                    ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, rack);

                                    // update the config spec fingerprint => Update statefulset having a different fingerprint if PHASE <> SCALE_...
                                    int replicas = v1StatefulSet.getSpec().getReplicas();

                                    logger.info("Rack {} updated by the rollback for datacenter {}",  movingZone.name, dataCenterMetadata.getName());
                                    logger.debug("DataCenter={} in namespace={} phase={} -> UPDATING",
                                            dataCenterMetadata.getName(), dataCenterMetadata.getNamespace(), dataCenterStatus.getPhase());

                                    // rackStatusByName.get(rack).setPhase(RackPhase.UPDATING);
                                    updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusByName);

                                    // StatefulSet update will not restart pending pods
                                    // we have to delete them to take in account the new statefulset configuration
                                    // https://github.com/kubernetes/kubernetes/issues/67250
                                    // This deletion step is for now a manual procedure.
                                    return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()
                                            .andThen(updateRack(zones, builder.buildStatefulSetRack(rack, replicas, configMapVolumeMounts, rackStatusByName.get(rack)), rack, rackStatusByName));
                                }
                                break;
                            case PARKING:

                                if (movingZone.isParked()) {
                                    movingRack.setJoinedReplicas(movingZone.size);
                                    movingRack.setPhase(RackPhase.PARKED);
                                    updateDatacenterStatus(zones.parkedDatacenter() ?  DataCenterPhase.PARKED : DataCenterPhase.PARKING, zones, rackStatusByName);
                                    logger.debug("PARKED done in rack={}", movingZone.name, movingZone.size);
                                }
                                break;

                            default:
                                for(int i = 0; i < movingZone.size; i++) {
                                    ElassandraPod pod = movingZone.pod(dataCenter, i);
                                    if (ElassandraNodeStatus.FAILED.equals(elassandraNodeStatusCache.getOrDefault(pod, ElassandraNodeStatus.UNKNOWN))) {
                                        failedPod = pod;
                                        movingRack.setPhase(RackPhase.FAILED);
                                        updateDatacenterStatus(DataCenterPhase.ERROR, zones, rackStatusByName);
                                        logger.debug("Pod={} FAILED in rack={} size={}", pod, movingZone.name, movingZone.size);
                                        break;
                                    }
                                }
                                logger.debug("phase={} NOP in rack={} size={}", movingRack.getPhase(), movingZone.name, movingZone.size);
                        }
                        // if a pod failed to start => set rack phase to FAILED, and authorize config update.
                        switch (movingRack.getPhase()) {
                            case RUNNING:
                            case PARKING:
                            case FAILED:
                                break;
                            default:
                                logger.debug("Waiting ongoing operation phase={} rack={} size={}", movingRack.getPhase(), movingZone.name, movingZone.size);
                                return Completable.complete();
                        }
                    }

                    // check all existing pod are UP and NORMAL before starting a new operation
                    int totalNormalPod = 0;
                    for (ElassandraPod pod : enumeratePods(existingStsMap)) {
                        ElassandraNodeStatus podStatus = Optional
                                .ofNullable(elassandraNodeStatusCache.get(pod))
                                .orElse(ElassandraNodeStatus.UNKNOWN);
                        switch(podStatus) {
                            case NORMAL:
                                totalNormalPod++;
                                break;
                            default:
                                logger.info("Pod name={} status={}, delaying operation.", pod.getName(), podStatus);
                                return Completable.complete();
                        }
                    }

                    Completable todo = Completable.complete();
                    final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
                    if (totalNormalPod > 0) {
                        // before scaling, if at least a pod is NORMAL, update keyspaces and roles if needed
                        todo = this.cqlKeyspaceManager.reconcileKeyspaces(dataCenter, cqlSessionHandler)
                                .andThen(this.cqlRoleManager.reconcileRole(dataCenter, cqlSessionHandler))
                                .andThen(this.cqlLicenseManager.verifyLicense(dataCenter, cqlSessionHandler))
                                .doFinally(new Action() {
                                    @Override
                                    public void run() throws Exception {
                                        cqlSessionHandler.close();
                                    }
                                });
                    }

                    // look up for the next rack to update if needed.
                    // if a pod is failed, only update the config in the same rack to avoid a general outage !
                    for (V1StatefulSet v1StatefulSet : existingStsMap.values()) {
                        String stsFingerprint = v1StatefulSet.getSpec().getTemplate().getMetadata().getAnnotations().get("configmap-fingerprint");
                        String rack = v1StatefulSet.getSpec().getTemplate().getMetadata().getLabels().get("rack");
                        ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, rack);
                        String configFingerprint = configMapVolumeMounts.fingerPrint();

                        // extract DC fingerPrint from STS metadata or return 0 if the entry is missing
                        final String stsDatacenterFingerprint = Optional.ofNullable(v1StatefulSet.getMetadata().getAnnotations())
                                .map(annotations -> annotations.get(OperatorLabels.DATACENTER_FINGERPRINT)).orElse("");

                        final RackStatus rackStatus = rackStatusByName.get(rack);
                        // Trigger an update if ConfigMap fingerprint or DC generation are different
                        if (rackStatus.isNotParked() && (!configFingerprint.equals(stsFingerprint) || !dataCenterSpec.fingerprint().equals(stsDatacenterFingerprint))) {
                            if (failedPod != null && !failedPod.getRack().equals(rack)) {
                                logger.warn("pod={} FAILED, cannot update other rack={} now, please fix the rack={} before.",
                                        failedPod, failedPod.getRack(), rack);
                            } else {
                                // update the config spec fingerprint => Update statefulset having a different fingerprint if PHASE <> SCALE_...
                                int replicas = v1StatefulSet.getSpec().getReplicas();
                                logger.debug("Need to update config fingerprint={} for statefulset={}, rack={}, replicas={}, phase={}",
                                        configFingerprint, v1StatefulSet.getMetadata().getName(), rack, replicas, dataCenterStatus.getPhase());
                                logger.debug("DataCenter={} in namespace={} phase={} -> UPDATING",
                                        dataCenterMetadata.getName(), dataCenterMetadata.getNamespace(), dataCenterStatus.getPhase());
                                rackStatus.setPhase(RackPhase.UPDATING);
                                updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusByName);
                                return todo
                                        .andThen(configMapVolumeMounts.createOrReplaceNamespacedConfigMaps())
                                        // updateRack also call prepareDataCenterSnapshot
                                        .andThen(updateRack(zones, builder.buildStatefulSetRack(rack, replicas, configMapVolumeMounts, rackStatus), rack, rackStatusByName));
                            }

                        } else if (dataCenterSpec.isParked() && rackStatus.isNotParked() ) {
                            // drain if performed by the shutdownHook of the Elassandra Container
                            return todo.andThen(parkRack(zones, v1StatefulSet, rack, rackStatusByName));
                        } else if (!dataCenterSpec.isParked() && rackStatus.isParked() ) {
                            return todo.andThen(unparkRack(zones, v1StatefulSet, rack, rackStatusByName));
                        }
                    }

                    if (failedPod != null) {
                        logger.info("pod={} FAILED, cannot scale the datacenter now", failedPod);
                        return todo;
                    }

                    Restore restoreFromBackup = dataCenterSpec.getRestoreFromBackup();
                    if (restoreRequired(zones, restoreFromBackup)) {
                        // restore is required, check that all saved nodes will have a new host
                        final String dcName = OperatorNames.dataCenterResource(dataCenterSpec.getClusterName(), dataCenterSpec.getDatacenterName());
                        final CloudStorageSecret cloudCredentials = k8sResourceUtils.readAndValidateStorageSecret(dataCenterMetadata.getNamespace(), restoreFromBackup.getSecretRef(), restoreFromBackup.getProvider());
                        ManifestReader reader = manifestReaderFactory.getManifestReader(dataCenter, restoreFromBackup, cloudCredentials);
                        GlobalManifest manifests = reader.aggregateManifest(restoreFromBackup.getSnapshotTag());

                        Set<String> nodesToRestore = manifests.getNodes();
                        Set<String> availableNodes = zones.zoneMap.values().stream().flatMap(zone -> {
                            String stsName = OperatorNames.stsName(dataCenter, zone.name);
                            return IntStream.range(0, zone.size).mapToObj(Integer::toString).map(index -> stsName+"-"+index);
                        }).collect(Collectors.toSet());

                        logger.debug("Restore : '{}' node to restore with '{}' available nodes", nodesToRestore.size(), availableNodes.size());
                        if (availableNodes.size() < nodesToRestore.size()) {
                            logger.error("Not enough nodes to restore the snapshot '{}', expected '{}' but '{}' are available", restoreFromBackup.getSnapshotTag(), nodesToRestore.size(), availableNodes.size());
                            throw new StrapkopException("Not enough nodes to restore the snapshot ["+availableNodes.size()+"/"+nodesToRestore.size()+"]");
                        }

                        if (!availableNodes.containsAll(nodesToRestore)) {
                            if (logger.isTraceEnabled()) {
                                logger.trace("Nodes to restore : {}", nodesToRestore);
                                logger.trace("Available nodes  : {}", availableNodes);
                            }
                            logger.error("Nodes topology doesn't match the previous one, restoration can't be processed");
                            throw new StrapkopException("Nodes topology is invalid");
                        }
                    }

                    if (!dataCenterSpec.isParked()) {
                        // DC isn't parked so we can scale up or down if require
                        if (zones.totalReplicas() < dataCenter.getSpec().getReplicas()) {
                            Optional<Zone> scaleUpZone = zones.nextToScalueUp();
                            if (scaleUpZone.isPresent()) {
                                Zone zone = scaleUpZone.get();
                                if (!zone.getSts().isPresent()) {
                                    final boolean firstStateFulSet = DataCenterPhase.CREATING.equals(dataCenterStatus.getPhase());
                                    // create new sts with replicas = 1,
                                    RackStatus rackStatus = new RackStatus()
                                            .setName(zone.name)
                                            .setPhase(RackPhase.CREATING)
                                            .setSeedHostId(UUID.randomUUID());
                                    rackStatusByName.put(zone.name, rackStatus);
                                    final V1StatefulSet sts = builder.buildStatefulSetRack(zones, zone.getName(), 1, rackStatus);
                                    updateDatacenterStatus(DataCenterPhase.SCALING_UP, zones, rackStatusByName);
                                    logger.debug("SCALE_UP started in rack={} size={}", zone.name, zone.size);

                                    todo = todo.andThen(
                                            k8sResourceUtils.createOrReplaceNamespacedService(builder.buildServiceSeed(zone.getName()))
                                                    .flatMapCompletable(s -> {
                                                        ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, zone.name);
                                                        return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps();
                                                    })
                                                    .andThen(createNamespacedStatefulSet(sts)));

                                    // we preserve the DataCenterSpec only for config update, but this is the very first Statefulset creation
                                    // so we also have to backup the DataCenterSpec.
                                    if (firstStateFulSet) {
                                        todo = todo.andThen(Completable.fromCallable(() -> {
                                            prepareDataCenterSnapshot(firstStateFulSet ? DataCenterPhase.CREATING : dataCenterStatus.getPhase(), sts);
                                            return sts;
                                        }));
                                    }

                                    return todo;
                                }
                                // +1 on sts replicas
                                V1StatefulSet sts = zone.getSts().get();
                                sts.getSpec().setReplicas(sts.getSpec().getReplicas() + 1);
                                dataCenterStatus.setNeedCleanup(true);
                                rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_UP);
                                updateDatacenterStatus(DataCenterPhase.SCALING_UP, zones, rackStatusByName);
                                logger.debug("SCALE_UP started in rack={} size={}", zone.name, zone.size);
                                if (sts.getSpec().getReplicas() > 1) {
                                    // call ConfigMapVolumeMount here to update seeds in case of single rack with multi-nodes
                                    ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, zone.name);
                                    return todo
                                            .andThen(sts.getSpec().getReplicas() > 1 ? configMapVolumeMounts.createOrReplaceNamespacedConfigMaps() : Completable.complete())
                                            .andThen(replaceNamespacedStatefulSet(sts));
                                } else {
                                    return todo
                                            .andThen(replaceNamespacedStatefulSet(sts));
                                }
                            }
                            logger.warn("Cannot scale up, no free node in datacenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
                        } else if (zones.totalReplicas() > dataCenter.getSpec().getReplicas()) {
                            Optional<Zone> scaleDownZone = zones.nextToScaleDown();
                            if (scaleDownZone.isPresent()) {
                                Zone zone = scaleDownZone.get();
                                V1StatefulSet sts = zone.getSts().get();
                                Optional<ElassandraPod> electedElassandraPod = electDecomissioningNode(zone);
                                if (electedElassandraPod.isPresent() && allReplicasRunnning(zone)) { // decommission a node only if all node of the rack are running in a NORMAL state
                                    ElassandraPod elassandraPod = electedElassandraPod.get();
                                    ElassandraNodeStatus elassandraNodeStatus = elassandraNodeStatusCache.getOrDefault(elassandraPod, ElassandraNodeStatus.UNKNOWN);
                                    // UNKNOWN, STARTING, NORMAL, JOINING, LEAVING, DECOMMISSIONED, MOVING, DRAINING, DRAINED, DOWN;
                                    switch (elassandraNodeStatus) {
                                        case NORMAL:
                                            // blocking call to decommission, max 5 times, with 2 second delays between each try
                                            // decommission node
                                            logger.debug("Adjusting RF and Decommissioning elassandra pod={}", elassandraPod);
                                            return todo
                                                    .andThen(cqlKeyspaceManager.decreaseRfBeforeScalingDownDc(dataCenter, zones.totalReplicas() - 1, cqlSessionHandler))
                                                    .andThen(Completable.fromAction(() -> {
                                                        // update the DC status after the decreaseRf because decreaseRf test DC phase is RUNNING...
                                                        rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_DOWN);
                                                        updateDatacenterStatus(DataCenterPhase.SCALING_DOWN, zones, rackStatusByName);
                                                        logger.debug("SCALE_DOWN started in rack={} size={}, decommissioning pod={} status={}",
                                                                zone.name, zone.size, elassandraPod, elassandraNodeStatus);
                                                    }))
                                                    .andThen(sidecarClientFactory.clientForPod(elassandraPod).decommission()
                                                            .retryWhen(errors -> errors
                                                                    .zipWith(Flowable.range(1, 5), (n, i) -> i)
                                                                    .flatMap(retryCount -> Flowable.timer(2, TimeUnit.SECONDS))
                                                            ));
                                        case DECOMMISSIONED:
                                        case DRAINED:
                                        case DOWN:

                                            // decrease the number of replicas only once... by testing the STS status
                                            if (sts.getStatus().getUpdatedReplicas() == sts.getStatus().getCurrentReplicas()
                                                    && sts.getStatus().getUpdatedReplicas() == sts.getStatus().getReadyReplicas()) {

                                                sts.getSpec().setReplicas(sts.getSpec().getReplicas() - 1);
                                                logger.info("Scaling down sts={} to {}, removing pod={}",
                                                        sts.getMetadata().getName(), sts.getSpec().getReplicas(), elassandraPod);
                                                rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_DOWN);
                                                updateDatacenterStatus(DataCenterPhase.SCALING_DOWN, zones, rackStatusByName);
                                                // scale down sts
                                                logger.debug("SCALE_DOWN started in rack={} size={}, removing pod={} status={}",
                                                        zone.name, zone.size, elassandraPod, elassandraNodeStatus);
                                                return todo.andThen(replaceNamespacedStatefulSet(sts));

                                            } else {
                                                logger.debug("SCALE_DOWN ongoing in rack={} size={}, removing pod={} status={}",
                                                        zone.name, zone.size, elassandraPod, elassandraNodeStatus);
                                                return todo;
                                            }

                                        default:
                                            logger.info("Waiting a valid status to remove pod={} from sts={} in namespace={}",
                                                    elassandraPod, sts.getMetadata().getName(), dataCenterMetadata.getNamespace());
                                    }
                                } else {
                                    logger.info("No pod eligible for Decommission from sts={} in namespace={}",
                                            sts.getMetadata().getName(), dataCenterMetadata.getNamespace());
                                }
                            } else {
                                logger.warn("Cannot scale down, no more replicas in datacenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
                            }
                        }
                    } else {
                        logger.debug("DataCenter PARKED, do not try to scale the cluster");
                    }
                    return todo;
                });
    }

    /**
     * commit the DataCenterSpec only if all STS use the same datacenter fingerprint
     * @param zones
     * @throws ApiException
     */
    private void commitDataCenterSnapshot(Zones zones) throws ApiException {
        if (zones.totalReplicas() == dataCenterSpec.getReplicas() && zones.isReady() && zones.hasConsistentConfiguration() &&
                zones.first().isPresent() && zones.first().get().getDataCenterFingerPrint().isPresent()) {
            RestorePointCache.commitRestorePoint(dataCenterSpec);
        }
    }

    private Completable replaceNamespacedStatefulSet(V1StatefulSet sts) throws ApiException {
        return k8sResourceUtils.replaceNamespacedStatefulSet(sts).ignoreElement();
    }

    private Completable createNamespacedStatefulSet(V1StatefulSet sts) throws ApiException {
        return k8sResourceUtils.createNamespacedStatefulSet(sts).ignoreElement();
    }

    private boolean allReplicasRunnning(Zone movingZone) {
        Integer expectedReplicas = movingZone.sts.get().getSpec().getReplicas();
        return (expectedReplicas == elassandraNodeStatusCache.countNodesInStateForRack(movingZone.name, ElassandraNodeStatus.NORMAL));
    }

    private Optional<ElassandraPod> electDecomissioningNode(Zone movingZone) {
        return elassandraNodeStatusCache.getLastBootstrappedNodesForRack(movingZone.name);
    }

    private void prepareDataCenterSnapshot(DataCenterPhase phase, V1StatefulSet statefulSet) throws ApiException {
        Optional<String> userConfigmap = Optional.ofNullable(statefulSet)
                .map((sts) -> sts.getMetadata().getAnnotations().get(OperatorLabels.CONFIGMAP_FINGERPRINT))
                .flatMap(this::extractUserConfigFingerPrint)
                .map(fingerprint -> OperatorNames.configMapUniqueName(dataCenterSpec.getUserConfigMapVolumeSource().getName(), fingerprint));
        RestorePointCache.prepareRestorePoint(dataCenterSpec, userConfigmap);
    }

    private Optional<String> extractUserConfigFingerPrint(final String statefulsetFingerPrint) {
        final String[] fingerprints = statefulsetFingerPrint.split("-");
        if (fingerprints.length == 2) {
            return Optional.ofNullable(fingerprints[1]);
        } else {
            return Optional.empty();
        }
    }

    private boolean restoreRequired(Zones zones, Restore restoreFromBackup) {
        return zones.totalReplicas() == 0 && restoreFromBackup != null && StringUtils.isNotEmpty(restoreFromBackup.getSnapshotTag());
    }


    public class ConfigMapVolumeMountBuilder {
        public final V1ConfigMap configMap;
        public final V1ConfigMapVolumeSource volumeSource;
        public final String mountName, mountPath;

        public ConfigMapVolumeMountBuilder(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this.configMap = configMap;
            this.mountName = mountName;
            this.mountPath = mountPath;
            if (volumeSource != null) {
                // copy the volume source to avoid name change when makeUnique is call
                // otherwise the datacenterSpec fingerprint will change too
                this.volumeSource = new V1ConfigMapVolumeSource();
                this.volumeSource.setName(volumeSource.getName());
                if (volumeSource.isOptional() != null) {
                    this.volumeSource.setOptional(volumeSource.isOptional().booleanValue());
                }
                if (volumeSource.getDefaultMode() != null) {
                    this.volumeSource.setDefaultMode(volumeSource.getDefaultMode().intValue());
                }
                if (volumeSource.getItems() != null) {
                    List<V1KeyToPath> items = new ArrayList<>();
                    this.volumeSource.setItems(items);
                    for (V1KeyToPath src : volumeSource.getItems()) {
                        items.add(new V1KeyToPath()
                                .key(src.getKey())
                                .path(src.getPath())
                                .mode(src.getMode()));
                    }
                }
            } else {
                this.volumeSource = null;
            }
        }

        public ConfigMapVolumeMountBuilder makeUnique() {
            if (configMap != null) {
                String hashedName = OperatorNames.configMapUniqueName(configMap.getMetadata().getName(), fingerPrint());
                configMap.getMetadata().setName(hashedName);// TODO [ELE] fully copy the configmap to include the cofgimap into the dc fingerprint
                volumeSource.setName(hashedName);
            }
            return this;
        }

        public ConfigMapVolumeMountBuilder(final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this(null, volumeSource, mountName, mountPath);
        }

        public Single<ConfigMapVolumeMountBuilder> createOrReplaceNamespacedConfigMap() throws ApiException {
            if (configMap != null) {
                return k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap).map(c->this);
            }
            return Single.just(this);
        }

        public String fingerPrint() {
            return builder.fingerPrint(configMap);
        }

        public ConfigMapVolumeMountBuilder addFile(final String path, final String content) {
            final String encodedKey = path.replaceAll("\\W", "_");

            configMap.putDataItem(encodedKey, content);
            volumeSource.addItemsItem(new V1KeyToPath().key(encodedKey).path(path));
            return this;
        }

        public V1VolumeMount buildV1VolumeMount() {
            return new V1VolumeMount()
                    .name(mountName)
                    .mountPath(mountPath);
        }
    }

    class ConfigMapVolumeMounts implements Iterable<ConfigMapVolumeMountBuilder> {
        public ConfigMapVolumeMountBuilder specConfig;  // configmap generated from CRD
        public ConfigMapVolumeMountBuilder userConfig;  // user provided configmap
        public ConfigMapVolumeMountBuilder seedConfig;  // per DC configmap, can be changed without trigering a rolling restart
        public ConfigMapVolumeMountBuilder rackConfig;  // per rack configmap

        public ConfigMapVolumeMounts(Zones zones, String rack) throws IOException, ApiException {
            this.specConfig = builder.buildConfigMapSpec();
            this.rackConfig = builder.buildConfigMapRack(rack);
            this.seedConfig = builder.buildConfigMapSeed(zones);
            if (dataCenterSpec.getUserConfigMapVolumeSource() != null) {
                // load and make user config unique here to mount the files
                this.userConfig = buildConfigMapUserConfig();
            }
        }

        private ConfigMapVolumeMountBuilder buildConfigMapUserConfig() {
            return k8sResourceUtils.readNamespacedConfigMap(dataCenterMetadata.getNamespace(), dataCenterSpec.getUserConfigMapVolumeSource().getName())
                    .map(configMap -> {
                        V1ObjectMeta meta = new V1ObjectMeta()
                                .name(configMap.getMetadata().getName())
                                .namespace(configMap.getMetadata().getNamespace())
                                .annotations(configMap.getMetadata().getAnnotations())
                                .labels(configMap.getMetadata().getLabels())
                                .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter));
                        V1ConfigMap configMap1 = new V1ConfigMap()
                                .metadata(meta)
                                .data(configMap.getData())
                                .binaryData(configMap.getBinaryData());
                        return new ConfigMapVolumeMountBuilder(configMap1, dataCenterSpec.getUserConfigMapVolumeSource(), "user-config-volume", "/tmp/user-config");
                    }).blockingGet();
        }

        public String fingerPrint() {
            return Optional.ofNullable(this.userConfig)
                    .map((uConfig -> this.specConfig.fingerPrint() + "-" +  uConfig.fingerPrint()))
                    .orElse(this.specConfig.fingerPrint()) ;
        }

        public Completable createOrReplaceNamespacedConfigMaps() throws ApiException {
            return specConfig.createOrReplaceNamespacedConfigMap().ignoreElement()
                    .andThen(rackConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    .andThen(seedConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    // use user configmap
                    .andThen((userConfig == null) ?
                            Completable.complete() :
                            userConfig.makeUnique().createOrReplaceNamespacedConfigMap().ignoreElement()
                    );
        }

        /**
         * Returns an iterator over elements of type {@code T}.
         *
         * @return an Iterator.
         */
        @Override
        public Iterator<ConfigMapVolumeMountBuilder> iterator() {
            List<ConfigMapVolumeMountBuilder> builders = new ArrayList<>(4);
            builders.add(specConfig);
            builders.add(rackConfig);
            builders.add(seedConfig);
            if (userConfig != null)
                builders.add(userConfig);
            return builders.iterator();
        }
    }

    public class Builder {

        /**
         * SHA1 first 7 caraters fingerprint of binaryData+data
         * @return
         */
        public String fingerPrint(V1ConfigMap configMap) {
            Map<String, Object> object = new HashMap<>(2);
            object.put("data", configMap.getData());
            if (configMap.getBinaryData() != null)
                object.put("binaryData", configMap.getBinaryData());
            return DigestUtils.sha1Hex(appsApi.getApiClient().getJSON().getGson().toJson(object)).substring(0,7);
        }

        public String nodetoolSsl() {
            return "-Djavax.net.ssl.trustStore=" + authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + " " +
                    "-Djavax.net.ssl.trustStorePassword=" + authorityManager.getCaTrustPass() + " " +
                    "-Djavax.net.ssl.trustStoreType=PKCS12 " +
                    "-Dcom.sun.management.jmxremote.registry.ssl=true";
        }

        private void addPortsItem(V1Container container, int port, String name, boolean withHostPort) {
            if (port > 0) {
                V1ContainerPort v1Port = new V1ContainerPort().name(name).containerPort(port);
                container.addPortsItem((dataCenterSpec.getHostPortEnabled() && withHostPort) ? v1Port.hostPort(port) : v1Port);
            }
        }

        public V1ObjectMeta clusterObjectMeta(final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                    .labels(OperatorLabels.cluster(dataCenterSpec.getClusterName()));
        }

        private V1ObjectMeta dataCenterObjectMeta(final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(dataCenterLabels)
                    .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
        }

        private V1ObjectMeta rackObjectMeta(final String rack, final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(OperatorLabels.rack(dataCenter, rack))
                    .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString())
                    .putAnnotationsItem(OperatorLabels.DATACENTER_FINGERPRINT, dataCenterSpec.fingerprint());
        }

        public  String clusterChildObjectName(final String nameFormat) {
            return String.format(nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
        }

        public String dataCenterResource(final String clusterName, final String dataCenterName) {
            return "elassandra-" + clusterName + "-" + dataCenterName;
        }

        public String dataCenterChildObjectName(final String nameFormat) {
            return String.format(nameFormat, dataCenterResource(dataCenterSpec.getClusterName(), dataCenter.getSpec().getDatacenterName()));
        }

        public String rackChildObjectName(final String nameFormat, final String rack) {
            return String.format(nameFormat,
                    "elassandra-" + dataCenter.getSpec().getClusterName()
                            + "-" + dataCenter.getSpec().getDatacenterName()
                            + "-" + rack);
        }




        public V1Service buildServiceExternalNodes() throws ApiException {
            return new V1Service()
                    .metadata(dataCenterObjectMeta(OperatorNames.externalService(dataCenter)))
                    .spec(new V1ServiceSpec()
                            .type("NodePort")
                            .addPortsItem(new V1ServicePort().name("internode").port(dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort()))
                            .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getNativePort()))
                            .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                            .addPortsItem(new V1ServicePort().name("jmx").port(dataCenterSpec.getJmxPort()))
                            // select any available pod in the DC, which is good only if internal seed service is good !
                            .selector(ImmutableMap.of(OperatorLabels.DATACENTER, dataCenter.getSpec().getDatacenterName()))
                    );
        }

        /**
         * Create a headless seed service per rack.
         * @param rack
         * @return
         * @throws ApiException
         */
        // No used any more
        public V1Service buildServiceSeed(String rack) throws ApiException {
            final V1ObjectMeta serviceMetadata = dataCenterObjectMeta(OperatorNames.seedsService(dataCenter))
                    // tolerate-unready-endpoints - allow the seed provider can discover the other seeds (and itself) before the readiness-probe gives the green light
                    .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");

            return new V1Service()
                    .metadata(serviceMetadata)
                    .spec(new V1ServiceSpec()
                            .publishNotReadyAddresses(true)
                            .clusterIP("None")
                            // a port needs to be defined for the service to be resolvable (#there-was-a-bug-ID-and-now-I-cant-find-it)
                            .ports(ImmutableList.of(
                                    new V1ServicePort().name("internode").port(dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort()),
                                    new V1ServicePort().name("jmx").port(dataCenterSpec.getJmxPort()))
                            )
                            // only select the pod 0 in a rack as seed, which is not good for local DC discovery (if rack X is unavailable).
                            // We should use
                            .selector(OperatorLabels.rack(dataCenter, rack))
                    );
        }

        public V1Service buildServiceNodes() {
            final V1ObjectMeta serviceMetadata = dataCenterObjectMeta(OperatorNames.nodesService(dataCenter));
            final V1Service service = new V1Service()
                    .metadata(serviceMetadata)
                    .spec(new V1ServiceSpec()
                            .clusterIP("None")
                            .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getNativePort()))
                            .addPortsItem(new V1ServicePort().name("jmx").port(dataCenterSpec.getJmxPort()))
                            .addPortsItem(new V1ServicePort().name("internode").port(dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort()))
                            .selector(dataCenterLabels)
                    );

            if (dataCenterSpec.getElasticsearchEnabled()) {
                service.getSpec().addPortsItem(new V1ServicePort().name("elasticsearch").port(9200));
            }

            if (dataCenterSpec.getPrometheusEnabled()) {
                service.getSpec().addPortsItem(new V1ServicePort().name("prometheus").port(9500));
            }
            return service;
        }

        public V1Service buildServiceElasticsearch() {
            return new V1Service()
                    .metadata(dataCenterObjectMeta(OperatorNames.elasticsearchService(dataCenter)))
                    .spec(new V1ServiceSpec()
                            .type("ClusterIP")
                            .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                            .selector(dataCenterLabels)
                    );
        }

        /**
         * Mutable configmap for seeds, one for all racks, does not require a rolling restart.
         * @return
         */
        public ConfigMapVolumeMountBuilder buildConfigMapSeed(Zones zones) {
            final V1ConfigMap configMap = new V1ConfigMap()
                    .metadata(dataCenterObjectMeta(OperatorNames.seedConfig(dataCenter)));

            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());

            // cassandra.yaml overrides
            Set<String> remoteSeeds = new HashSet<>();
            if (dataCenterSpec.getRemoteSeeds() != null && !dataCenterSpec.getRemoteSeeds().isEmpty()) {
                remoteSeeds.addAll(dataCenterSpec.getRemoteSeeds().stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            }

            Set<String> remoteSeeders = new HashSet<>();
            if (dataCenterSpec.getRemoteSeeders() != null && !dataCenterSpec.getRemoteSeeders().isEmpty()) {
                remoteSeeders.addAll(dataCenterSpec.getRemoteSeeders().stream().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
            }

            Set<String> seeds = new HashSet<>();
            for(RackStatus rackStatus : dataCenterStatus.getRackStatuses().values()) {
                if (rackStatus.getJoinedReplicas() > 0 && dataCenterSpec.getReplicas() > 1) // also test the nb of expected replicas to avoid crashloopbackup in a single node configuration update
                    seeds.add(new ElassandraPod(dataCenter, rackStatus.getName(), 0).getFqdn());
            }

            Map<String, String> parameters = new HashMap<>();
            if (!seeds.isEmpty())
                parameters.put("seeds", String.join(", ", seeds));
            if (!remoteSeeds.isEmpty())
                parameters.put("remote_seeds", String.join(", ", seeds));
            if (!remoteSeeders.isEmpty())
                parameters.put("seeders", String.join(", ", remoteSeeders));
            logger.debug("seed parameters={}", parameters);
            final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null
            config.put("seed_provider", ImmutableList.of(ImmutableMap.of(
                    "class_name", ElassandraOperatorSeedProviderAndNotifier.class.getName(),
                    "parameters", ImmutableList.of(parameters))
            ));
            return new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume-seeds", "/tmp/operator-config-seeds")
                    .addFile("cassandra.yaml.d/003-cassandra-seeds.yaml", toYamlString(config));
        }

        /**
         * One configmap per sts, mutable and suffixed by a hash of the spec data
         * @return
         * @throws IOException
         */
        public ConfigMapVolumeMountBuilder  buildConfigMapSpec() throws IOException {
            final V1ConfigMap configMap = new V1ConfigMap().metadata(dataCenterObjectMeta(OperatorNames.specConfig(dataCenter)));
            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
            final ConfigMapVolumeMountBuilder configMapVolumeMountBuilder =
                    new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume-spec", "/tmp/operator-config-spec");

            // cassandra.yaml overrides
            {
                final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null

                config.put("cluster_name", dataCenterSpec.getClusterName());
                config.put("num_tokens", "16");

                config.put("listen_address", null); // let C* discover the listen address
                // broadcast_rpc is set dynamically from entry-point.sh according to env $POD_IP
                config.put("rpc_address", "0.0.0.0"); // bind rpc to all addresses (allow localhost access)

                config.put("storage_port", dataCenterSpec.getStoragePort());
                config.put("ssl_storage_port", dataCenterSpec.getSslStoragePort());
                config.put("native_transport_port", dataCenterSpec.getNativePort());

                // compute the number os CPU to adapt the ConcurrentWriter settings (force a min to 1 to avoid the a ThreadPool of 0)
                final int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
                // writer recommendation is 8 * CPUs
                final int concurrentWriter = cpu * 8;
                config.put("concurrent_writes", concurrentWriter);
                config.put("concurrent_materialized_view_writes", concurrentWriter);

                // reader recommendation is based on the number of disk (nbDisk * 16)
                // so leave the default value or set to nb of writer if writers are more than 32...
                final int concurrentReader = Math.max(32, concurrentWriter);
                config.put("concurrent_reads", concurrentReader);
                // counter use the same as reader because counter read the value before increment & write value
                config.put("concurrent_counter_writes", concurrentReader);

                // value used by : https://blog.deimos.fr/2018/06/24/running-cassandra-on-kubernetes/
                config.put("hinted_handoff_throttle_in_kb", 4096);

                if (dataCenterSpec.getWorkload() != null) {
                    if (dataCenterSpec.getWorkload().equals(Workload.READ)
                            || dataCenterSpec.getWorkload().equals(Workload.READ_WRITE)) {
                        // because we are in a read heavy workload, we set the cache to 100MB
                        // (the max value of the auto setting -  (min(5% of Heap (in MB), 100MB)) )
                        config.put("key_cache_size_in_mb", 100);
                    }

                    if (dataCenterSpec.getWorkload().equals(Workload.WRITE)
                            || dataCenterSpec.getWorkload().equals(Workload.READ_WRITE)) {
                        // configure memtable_flush_writers has an influence on the memtable_cleanup_threshold (1/(nb_flush_w + 1))
                        // so we set a little bit higher value for Write Heavy Workload
                        // default is 1/(memtable_flush_writers +1) ==> 1/3
                        // increase the number of memtable flush, increase the frequncy of memtable flush
                        // cpu = 1 ==> 1
                        // cpu = 2 ==> 2
                        // cpu = 4 ==> 2
                        // cpu = 8 ==> 4
                        // cpu = 16 ==> 8
                        final int flusher = Math.min(cpu, Math.max(2, cpu / 2));
                        config.put("memtable_flush_writers", flusher);

                        // https://tobert.github.io/pages/als-cassandra-21-tuning-guide.html
                        // Offheap memtables can improve write-heavy workloads by reducing the amount of data stored on the Java heap
                        config.put("memtable_allocation_type", "offheap_objects");
                    }

                    if (dataCenterSpec.getWorkload().equals(Workload.READ_WRITE)) {
                        // The faster you insert data, the faster you need to compact in order to keep the sstable count down,
                        // but in general, setting this to 16 to 32 times the rate you are inserting data is more than sufficient.
                        config.put("compaction_throughput_mb_per_sec", 24); // default is 16 - set to 24 to increase the compaction speed
                    }
                }

                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-spec.yaml", toYamlString(config));
            }

            // prometheus support (see prometheus annotations)
            if (dataCenterSpec.getPrometheusEnabled()) {
            /*
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/001-cassandra-exporter.sh",
                "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/cassandra-exporter-agent.jar=@${CASSANDRA_CONF}/cassandra-exporter.conf\"");
            */

                // jmx-promtheus exporter
                // TODO: use version less symlink to avoid issues when upgrading the image
                configMapVolumeMountBuilder.addFile( "cassandra-env.sh.d/001-cassandra-exporter.sh",
                        "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/jmx_prometheus_javaagent.jar=9500:${CASSANDRA_CONF}/jmx_prometheus_exporter.yml\"");
            }

            StringBuilder jvmOptionsD = new StringBuilder(500);
            jvmOptionsD.append("-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort()+"\n");

            // Add JMX configuration
            if (dataCenterSpec.getJmxmpEnabled()) {
                // JMXMP is fine, but visualVM cannot use jmxmp+tls+auth
                jvmOptionsD.append("-Dcassandra.jmxmp=true\n");
            }

            // Remote JMX require SSL, otherwise this is local clear JMX
            if (useJmxOverSSL()) {
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.authenticate=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n");
                //"-Dcom.sun.management.jmxremote.access.file=/etc/cassandra/jmxremote.access\n" + \
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.ssl=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.registry.ssl=true\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStore=" + OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStorePassword=" + OPERATOR_KEYPASS + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.keyStoreType=PKCS12\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStore=" + authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStorePassword=" + authorityManager.getCaTrustPass() + "\n");
                jvmOptionsD.append("-Djavax.net.ssl.trustStoreType=PKCS12");
            } else {
                // local JMX, clear + no auth
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.authenticate=true\n");
                jvmOptionsD.append("-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n");
                jvmOptionsD.append("-Djava.rmi.server.hostname=127.0.0.1\n");
                jvmOptionsD.append("-XX:+DisableAttachMechanism");
            }
            configMapVolumeMountBuilder.addFile("jvm.options.d/001-jmx.options", jvmOptionsD.toString());

            // Add jdb transport socket
            if (dataCenterSpec.getJdbPort() > 0) {
                configMapVolumeMountBuilder.addFile("cassandra-env.sh.d/001-cassandra-jdb.sh",
                        "JVM_OPTS=\"${JVM_OPTS} -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=" + dataCenterSpec.getJdbPort() + "\"");
            }

            // this does not work with elassandra because it needs to run as root. It has been moved to the init container
            // tune ulimits
            // configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-cassandra-limits.sh",
            //        "ulimit -l unlimited\n" // unlimited locked memory
            //);

            // heap size and GC settings
        /*
        {
            final long memoryLimit = dataCenterSpec.getResources().getLimits().get("memory").getNumber().longValue();
            if (memoryLimit < 3.5 * GB) {
                throw new IllegalArgumentException("Cannot deploy elassandra with less than 3.5Gb Memory limits, please increase your kubernetes memory limits");
            }

            final long cpuLimit =  dataCenterSpec.getResources().getLimits().get("cpu").getNumber().longValue();
            final int coreCount = (int) cpuLimit/1000;

            // same as stock cassandra-env.sh
            final double jvmHeapSizeInGb = Math.max(
                    Math.min(memoryLimit / 2, 1.5 * GB),
                    Math.min(memoryLimit / 4, 8 * GB)
            );

            final double youngGenSizeInMb = Math.min(
                    100 * MB * coreCount,
                    jvmHeapSizeInGb * 1024 / 4
            );

            logger.debug("cluster={} dc={} namespace={} memoryLimit={} cpuLimit={} coreCount={} jvmHeapSizeInGb={} youngGenSizeInMb={}",
                    dataCenterSpec.getClusterName(), dataCenterSpec.getDatacenterName(), dataCenterMetadata.getNamespace(),
                    memoryLimit, cpuLimit, coreCount, jvmHeapSizeInGb, youngGenSizeInMb);
            if (jvmHeapSizeInGb < 1.2 * GB) {
                throw new IllegalArgumentException("Cannot deploy elassandra with less than 1.2Gb heap, please increase your kubernetes memory limits");
            }

            final boolean useG1GC = (jvmHeapSizeInGb > 16 * GB);
            final StringWriter writer = new StringWriter();
            try (final PrintWriter printer = new PrintWriter(writer)) {
                printer.format("-Xms%d%n", (long) jvmHeapSizeInGb); // min heap size
                printer.format("-Xmx%d%n", (long) jvmHeapSizeInGb); // max heap size

                // copied from stock jvm.options
                if (useG1GC) {
                    printer.println("-XX:+UseG1GC");
                    printer.println("-XX:G1RSetUpdatingPauseTimePercent=5");
                    printer.println("-XX:MaxGCPauseMillis=500");

                    if (jvmHeapSizeInGb > 12 * GB) {
                        printer.println("-XX:InitiatingHeapOccupancyPercent=70");
                    }

                    // TODO: tune -XX:ParallelGCThreads, -XX:ConcGCThreads
                } else {
                    printer.format("-Xmn%d%n", (long)youngGenSizeInMb); // young gen size

                    printer.println("-XX:+UseParNewGC");
                    printer.println("-XX:+UseConcMarkSweepGC");
                    printer.println("-XX:+CMSParallelRemarkEnabled");
                    printer.println("-XX:SurvivorRatio=8");
                    printer.println("-XX:MaxTenuringThreshold=1");
                    printer.println("-XX:CMSInitiatingOccupancyFraction=75");
                    printer.println("-XX:+UseCMSInitiatingOccupancyOnly");
                    printer.println("-XX:CMSWaitDuration=10000");
                    printer.println("-XX:+CMSParallelInitialMarkEnabled");
                    printer.println("-XX:+CMSEdenChunksRecordAlways");
                    printer.println("-XX:+CMSClassUnloadingEnabled");
                }

                // OOM Error handling
                printer.println("-XX:+HeapDumpOnOutOfMemoryError");
                printer.println("-XX:+CrashOnOutOfMemoryError");
            }

            configMapVolumeAddFile(configMap, volumeSource, "jvm.options.d/001-jvm-memory-gc.options", writer.toString());
        }
        */

            // TODO: maybe tune -Dcassandra.available_processors=number_of_processors - Wait till we build C* for Java 11
            // not sure if k8s exposes the right number of CPU cores inside the container

            // add SSL config
            if (dataCenterSpec.getSsl()) {
                final Map<String, Object> cassandraConfig = new HashMap<>();
                cassandraConfig.put("server_encryption_options", ImmutableMap.builder()
                        .put("internode_encryption", "all")
                        .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE)
                        .put("keystore_password", OPERATOR_KEYPASS)
                        .put("truststore",  authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 )
                        .put("truststore_password", authorityManager.getCaTrustPass())
                        .put("protocol", "TLSv1.2")
                        .put("algorithm", "SunX509")
                        .put("store_type", "PKCS12")
                        //.put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_256_CBC_SHA"))
                        .put("require_client_auth", true)
                        .build()
                );
                cassandraConfig.put("client_encryption_options", ImmutableMap.builder()
                        .put("enabled", true)
                        .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE)
                        .put("keystore_password", OPERATOR_KEYPASS)
                        .put("truststore",  authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 )
                        .put("truststore_password", authorityManager.getCaTrustPass())
                        .put("protocol", "TLSv1.2")
                        .put("store_type", "PKCS12")
                        .put("algorithm", "SunX509")
                        .put("require_client_auth", false)
                        //.put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))
                        .build()
                );
                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/002-ssl.yaml", toYamlString(cassandraConfig));
            }

            // add authentication config
            switch (dataCenterSpec.getAuthentication()) {
                case NONE:
                    break;
                case CASSANDRA:
                    configMapVolumeMountBuilder.addFile( "cassandra.yaml.d/002-authentication.yaml",
                            toYamlString(ImmutableMap.of(
                                    "authenticator", "PasswordAuthenticator",
                                    "authorizer", "CassandraAuthorizer")));
                    break;
                case LDAP:
                    configMapVolumeMountBuilder.addFile( "cassandra.yaml.d/002-authentication.yaml",
                            toYamlString(ImmutableMap.of(
                                    "authenticator", "com.strapdata.cassandra.ldap.LDAPAuthenticator",
                                    "authorizer", "CassandraAuthorizer",
                                    "role_manager", "com.strapdata.cassandra.ldap.LDAPRoleManager")));
                    //TODO: Add ldap.properties + ldap.pem +
                    // -Dldap.properties.file=/usr/share/cassandra/conf/ldap.properties
                    // -Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true"
                    break;
            }

            // add elasticsearch config support
            final Enterprise enterprise = dataCenterSpec.getEnterprise();
            if (enterprise.getEnabled()) {
                final Map<String, Object> esConfig = new HashMap<>();

                esConfig.put("jmx", ImmutableMap.of("enabled", enterprise.getJmx()));
                esConfig.put("https", ImmutableMap.of("enabled", enterprise.getHttps()));
                esConfig.put("ssl", ImmutableMap.of("transport", ImmutableMap.of("enabled", enterprise.getSsl())));

                if (enterprise.getAaa() == null) {
                    esConfig.put("aaa", ImmutableMap.of("enabled", false));
                } else {
                    esConfig.put("aaa", ImmutableMap.of(
                            "enabled", enterprise.getAaa().getEnabled(),
                            "audit", ImmutableMap.of("enabled", enterprise.getAaa().getAudit())
                    ));
                }

                esConfig.put("cbs", ImmutableMap.of("enabled", enterprise.getCbs()));
                configMapVolumeMountBuilder.addFile("elasticsearch.yml.d/002-enterprise.yaml", toYamlString(esConfig));
                configMapVolumeMountBuilder.addFile( "cassandra-env.sh.d/002-enterprise.sh",
                        "JVM_OPTS=\"$JVM_OPTS -Dcassandra.custom_query_handler_class=org.elassandra.index.EnterpriseElasticQueryHandler" +
                                " -D"+ElassandraOperatorSeedProviderAndNotifier.STATUS_NOTIFIER_URL+"=http://strapkop-elassandra-operator:8080/node/"+dataCenterMetadata.getNamespace()
                                +"\"");
                // TODO: override com exporter in cassandra-env.sh.d/001-cassandra-exporter.sh
            }

            // add elassandra datacenter.group config
            if (dataCenterSpec.getDatacenterGroup() != null) {
                final Map<String, Object> esConfig = new HashMap<>();
                esConfig.put("datacenter", ImmutableMap.of("group", dataCenterSpec.getDatacenterGroup()));
                configMapVolumeMountBuilder.addFile("elasticsearch.yml.d/003-datacentergroup.yaml", toYamlString(esConfig));
            }

            return configMapVolumeMountBuilder.makeUnique();
        }

        /**
         * configuration that is specific to rack. For the moment, an update of it does not trigger a restart
         * One immutable configmap per rack
         */
        private ConfigMapVolumeMountBuilder buildConfigMapRack(final String rack) throws IOException, ApiException {
            final V1ConfigMap configMap = new V1ConfigMap().metadata(rackObjectMeta(rack, OperatorNames.rackConfig(dataCenter, rack)));
            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());

            // GossipingPropertyFileSnitch config
            final Properties rackDcProperties = new Properties();
            rackDcProperties.setProperty("dc", dataCenterSpec.getDatacenterName());
            rackDcProperties.setProperty("rack", rack);
            rackDcProperties.setProperty("prefer_local", "true");

            final StringWriter writer = new StringWriter();
            rackDcProperties.store(writer, "generated by cassandra-operator");
            // Set default Dc:rack in cassandra-topology.properties to avoid inconsistent nodetool status when a node is down.
            // This is because GossipingPropertyFileSnitch inherits from PropertyFileSnitch
            return new ConfigMapVolumeMountBuilder(configMap, volumeSource, "operator-config-volume-rack", "/tmp/operator-config-rack")
                    .addFile("cassandra-rackdc.properties", writer.toString())
                    .addFile("cassandra-topology.properties", String.format(Locale.ROOT, "default=%s:%s", dataCenterSpec.getDatacenterName(), rack));
        }

        public V1Secret buildSecretKeystore(X509CertificateAndPrivateKey x509CertificateAndPrivateKey) throws GeneralSecurityException, IOException, OperatorCreationException {
            final V1ObjectMeta certificatesMetadata = dataCenterObjectMeta(OperatorNames.keystoreSecret(dataCenter));

            // generate statefulset wildcard certificate in a PKCS12 keystore
            final String wildcardStatefulsetName = "*." + OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            final String headlessServiceName = OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            final String elasticsearchServiceName = OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            @SuppressWarnings("UnstableApiUsage") final V1Secret certificatesSecret = new V1Secret()
                    .metadata(certificatesMetadata)
                    .putDataItem("keystore.p12",
                            authorityManager.issueCertificateKeystore(
                                    x509CertificateAndPrivateKey,
                                    wildcardStatefulsetName,
                                    ImmutableList.of(wildcardStatefulsetName, headlessServiceName, elasticsearchServiceName, "localhost"),
                                    ImmutableList.of(InetAddresses.forString("127.0.0.1")),
                                    dataCenterMetadata.getName(),
                                    "changeit"
                            ))
                    // add a client certificate in a PKCS12 keystore for TLS client auth
                    .putDataItem("client-keystore.p12",
                            authorityManager.issueCertificateKeystore(
                                    x509CertificateAndPrivateKey,
                                    "client",
                                    ImmutableList.of(),
                                    ImmutableList.of(),
                                    dataCenterMetadata.getName(),
                                    "changeit"
                            ));

            return certificatesSecret;
        }

        /**
         * Generate rc files as secret (.curlrc and .cassandra/cqlshrc + .cassandra/nodetool-ssl.properties)
         * TODO: avoid generation on each reconciliation
         * @param username
         * @param password
         */
        public V1Secret buildSecretRcFile(String username, String password)  {
            String cqlshrc = "";
            String curlrc = "";

            if (dataCenterSpec.getSsl()) {
                cqlshrc += "[connection]\n" +
                        "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                        "port = " + dataCenterSpec.getNativePort() + "\n" +
                        "ssl = true\n" +
                        "\n" +
                        "[ssl]\n" +
                        "certfile = " + authorityManager.getPublicCaMountPath() + "/cacert.pem\n" +
                        "validate = true\n";

                if (Optional.ofNullable(dataCenterSpec.getEnterprise()).map(Enterprise::getSsl).orElse(false)) {
                    curlrc += "cacert = " + authorityManager.getPublicCaMountPath() + "/cacert.pem\n";
                }
            } else {
                cqlshrc += "[connection]\n" +
                        "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                        "port = " + dataCenterSpec.getNativePort() + "\n";
            }

            if (!dataCenterSpec.getAuthentication().equals(Authentication.NONE)) {
                cqlshrc += String.format(Locale.ROOT, "[authentication]\n" +
                        "username = %s\n" +
                        "password = %s", username, password);
                if (Optional.ofNullable(dataCenterSpec.getEnterprise()).map(Enterprise::getAaa).map(Aaa::getEnabled).orElse(false)) {
                    curlrc += String.format(Locale.ROOT, "user = %s:%s\n", username, password);
                }
            }

            final V1ObjectMeta secretMetadata = clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter));
            final V1Secret secret = new V1Secret()
                    .metadata(secretMetadata)
                    .putStringDataItem("cqlshrc", cqlshrc)
                    .putStringDataItem("curlrc", curlrc);
            if (dataCenterSpec.getSsl())
                secret.putStringDataItem("nodetool-ssl.properties", nodetoolSsl());
            return secret;
        }

        public V1StatefulSet buildStatefulSetRack(Zones zones, String rack, int replicas, RackStatus rackStatus) throws IOException, ApiException, StrapkopException {
            return buildStatefulSetRack(rack, replicas, new ConfigMapVolumeMounts(zones, rack), rackStatus);
        }

        public V1StatefulSet buildStatefulSetRack(final String rack, final int replicas, ConfigMapVolumeMounts configMapVolumeMounts, RackStatus rackStatus) throws ApiException, StrapkopException {
            final V1ObjectMeta statefulSetMetadata = rackObjectMeta(rack, OperatorNames.stsName(dataCenter, rack));

            // create Elassandra container and the associated initContainer to replay commitlogs
            final V1Container cassandraContainer = buildElassandraContainer(rackStatus.getSeedHostId());
            final V1Container commitlogInitContainer = buildInitContainerCommitlogReplayer(rackStatus.getSeedHostId());

            if (dataCenterSpec.getPrometheusEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name("prometheus").containerPort(9500));
            }

            final V1Container sidecarContainer = new V1Container()
                    .name("sidecar")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .env(new ArrayList<>(dataCenterSpec.getEnv()))
                    .addEnvItem(new V1EnvVar()
                            .name("ELASSANDRA_USERNAME")
                            .value("elassandra_operator")
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("ELASSANDRA_PASSWORD")
                            .valueFrom(new V1EnvVarSource()
                                    .secretKeyRef(new V1SecretKeySelector()
                                            .name(OperatorNames.clusterSecret(dataCenter))
                                            .key(CqlRole.KEY_ELASSANDRA_OPERATOR_PASSWORD )))
                    )
                    .image(dataCenterSpec.getSidecarImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .securityContext(new V1SecurityContext().runAsUser(CASSANDRA_USER_ID).runAsGroup(CASSANDRA_GROUP_ID))
                    .addPortsItem(new V1ContainerPort().name("http").containerPort(8080))
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("data-volume")
                            .mountPath("/var/lib/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("sidecar-config-volume")
                            .mountPath("/tmp/sidecar-config-volume")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("cassandra-log-volume")
                            .mountPath("/var/log/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("nodeinfo")
                            .mountPath("/nodeinfo")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("sidecar-truststore-volume")
                            .mountPath("/tmp/sidecar-truststore")
                    )
                    .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                    .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("JMX_PORT").value(Integer.toString(dataCenterSpec.getJmxPort())))
                    .addEnvItem(new V1EnvVar().name("SIDECAR_SSL_ENABLE").value(dataCenterSpec.getSsl().toString()))
                    .addEnvItem(new V1EnvVar().name("SEED_HOST_ID").value(rackStatus.getSeedHostId().toString()))
                    ;

            if (operatorConfig.getDnsAzureSecretName() != null && operatorConfig.isDnsUpdaterEnabled()) {
                addDnsAzureServicePrincipal(sidecarContainer, operatorConfig.getDnsAzureSecretName());
            } else {
                sidecarContainer.addEnvItem(new V1EnvVar().name("DNS_UPDATER_ENABLED").value("false"));
            }

            if (dataCenterSpec.getSsl()) {
                sidecarContainer
                        .addPortsItem(new V1ContainerPort().name("https").containerPort(8443))
                        .addEnvItem(new V1EnvVar()
                                .name("SIDECAR_SSL_KEYSTORE_SECRET")
                                .value(OPERATOR_KEYPASS))// TODO [ELE] how to set pwd when keystore define by customer...?
                        .addEnvItem(new V1EnvVar()
                                .name("SIDECAR_SSL_KEYSTORE_PATH")
                                .value("file:"+OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE));
            }

            {
                String javaToolOptions = "";
                // WARN: Cannot enable SSL on JMXMP because VisualVM does not support it => JMXMP in clear with no auth
                javaToolOptions += dataCenterSpec.getJmxmpEnabled() ? " -Dcassandra.jmxmp " : "";
                javaToolOptions += (useJmxOverSSL() ?
                        "-Dssl.enable=true " +
                        "-Dcom.sun.management.jmxremote.registry.ssl=true " +
                        "-Djavax.net.ssl.trustStore=/tmp/sidecar-truststore/cacerts " +
                        "-Djavax.net.ssl.trustStorePassword=changeit " :
                        "");
                if (javaToolOptions.length() > 0) {
                    sidecarContainer.addEnvItem(new V1EnvVar().name("JAVA_TOOL_OPTIONS").value(javaToolOptions));
                }
            }

            final V1PodSpec podSpec = new V1PodSpec()
                    .securityContext(new V1PodSecurityContext().fsGroup(CASSANDRA_GROUP_ID))
                    .hostNetwork(dataCenterSpec.getHostNetworkEnabled())
                    .addInitContainersItem(buildInitContainerVmMaxMapCount())
                    .addInitContainersItem(buildInitContainerMergeTrustCerts())
                    .addContainersItem(cassandraContainer)
                    .addContainersItem(sidecarContainer)
                    .addVolumesItem(new V1Volume()
                            .name("pod-info")
                            .downwardAPI(new V1DownwardAPIVolumeSource()
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("labels")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.labels"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("annotations")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.annotations"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("namespace")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))
                                    )
                                    .addItemsItem(new V1DownwardAPIVolumeFile()
                                            .path("name")
                                            .fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))
                                    )
                            )
                    )
                    .addVolumesItem(new V1Volume()
                            .name("sidecar-config-volume")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("sidecar-truststore-volume")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("cassandra-log-volume")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("nodeinfo")
                            .emptyDir(new V1EmptyDirVolumeSource())
                    )
                    .addVolumesItem(new V1Volume()
                            .name("cqlshrc-volume")
                            .secret(new V1SecretVolumeSource()
                                    .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                    .addItemsItem(new V1KeyToPath()
                                            .key("cqlshrc").path("cqlshrc").mode(256)
                                    )
                            )
                    )
                    .addVolumesItem(new V1Volume()
                            .name("curlrc-volume")
                            .secret(new V1SecretVolumeSource()
                                    .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                    .addItemsItem(new V1KeyToPath()
                                            .key("curlrc").path(".curlrc").mode(256)
                                    )
                            )
                    )
                    ;

            if (dataCenterSpec.getSsl()) {
                podSpec.addVolumesItem(new V1Volume()
                        .name("nodetool-ssl-volume")
                        .secret(new V1SecretVolumeSource()
                                .secretName(OperatorNames.clusterRcFilesSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath()
                                        .key("nodetool-ssl.properties").path("nodetool-ssl.properties").mode(256)
                                )
                        )
                );
            }

            // Add the nodeinfo init container if we have the nodeinfo secret name provided in the env var NODEINFO_SECRET
            // To create such a service account:
            // kubectl create serviceaccount --namespace default nodeinfo
            // kubectl create clusterrolebinding nodeinfo-cluster-rule --clusterrole=nodeinfo --serviceaccount=default:nodeinfo
            // kubectl get serviceaccount nodeinfo -o json | jq ".secrets[0].name"
            if (!Strings.isNullOrEmpty(System.getenv("ELASSANDRA_OPERATOR_NAME"))) {
                // lookup nodeinfo secret name (dash and underscore forbidden by GCP)
                String name = System.getenv("ELASSANDRA_OPERATOR_NAME")+"nodeinfo";
                String nodeInfoSecretName = k8sResourceUtils.readNamespacedServiceAccount(dataCenterMetadata.getNamespace(), name).getSecrets().get(0).getName();
                podSpec.addInitContainersItem(buildInitContainerNodeInfo(nodeInfoSecretName));
            } else if (!Strings.isNullOrEmpty(System.getenv("NODEINFO_SECRET"))) {
                String nodeInfoSecretName = System.getenv("NODEINFO_SECRET");
                podSpec.addInitContainersItem(buildInitContainerNodeInfo(nodeInfoSecretName));
            }

            {
                if (dataCenterSpec.getImagePullSecrets() != null) {
                    for(String secretName : dataCenterSpec.getImagePullSecrets()) {
                        final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secretName);
                        podSpec.addImagePullSecretsItem(pullSecret);
                    }
                }
            }

            // add node affinity for rack, strict or slack (depends on cloud providers PVC cross-zone support)
            final V1NodeSelectorTerm nodeSelectorTerm = new V1NodeSelectorTerm()
                    .addMatchExpressionsItem(new V1NodeSelectorRequirement()
                            .key(OperatorLabels.ZONE).operator("In").addValuesItem(rack));
            switch (dataCenterSpec.getPodsAffinityPolicy()) {
                case STRICT:
                    podSpec.affinity(new V1Affinity()
                            .nodeAffinity(new V1NodeAffinity()
                                    .requiredDuringSchedulingIgnoredDuringExecution(new V1NodeSelector()
                                            .addNodeSelectorTermsItem(nodeSelectorTerm))));
                    break;
                case SLACK:
                    podSpec.affinity(new V1Affinity()
                            .nodeAffinity(new V1NodeAffinity()
                                    .addPreferredDuringSchedulingIgnoredDuringExecutionItem(new V1PreferredSchedulingTerm()
                                            .weight(100).preference(nodeSelectorTerm))));
                    break;
            }

            // add configmap volumes
            for (final ConfigMapVolumeMountBuilder configMapVolumeMountBuilder : configMapVolumeMounts) {
                logger.debug("Adding configMapVolumeMount name={} path={}", configMapVolumeMountBuilder.mountName, configMapVolumeMountBuilder.mountPath);
                cassandraContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());
                // the Cassandra container entrypoint overlays configmap volumes
                cassandraContainer.addArgsItem(configMapVolumeMountBuilder.mountPath);

                // commitlogInitContainer must have same volumes as the elassandra one
                commitlogInitContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());
                commitlogInitContainer.addArgsItem(configMapVolumeMountBuilder.mountPath);

                // provide access to config map volumes in the sidecar, these reside in /tmp though and are not overlayed into /etc/cassandra
                sidecarContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());

                podSpec.addVolumesItem(new V1Volume()
                        .name(configMapVolumeMountBuilder.mountName)
                        .configMap(configMapVolumeMountBuilder.volumeSource)
                );
            }

            if (dataCenterSpec.getUserSecretVolumeSource() != null) {
                V1VolumeMount userSecretVolMount = new V1VolumeMount()
                        .name("user-secret-volume")
                        .mountPath("/tmp/user-secret-config");
                cassandraContainer.addVolumeMountsItem(userSecretVolMount);
                commitlogInitContainer.addVolumeMountsItem(userSecretVolMount);

                podSpec.addVolumesItem(new V1Volume()
                        .name("user-secret-volume")
                        .secret(dataCenterSpec.getUserSecretVolumeSource())
                );
            }

            // mount JMX password to a env variable for elassandra and sidecar
            final V1EnvVar jmxPasswordEnvVar = new V1EnvVar()
                    .name("JMX_PASSWORD")
                    .valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector()
                            .name(OperatorNames.clusterSecret(dataCenter))
                            .key(KEY_JMX_PASSWORD)));
            cassandraContainer.addEnvItem(jmxPasswordEnvVar);
            commitlogInitContainer.addEnvItem(jmxPasswordEnvVar);
            sidecarContainer.addEnvItem(jmxPasswordEnvVar);

            // mount SSL keystores
            if (dataCenterSpec.getSsl()) {
                V1VolumeMount opKeystoreVolMount = new V1VolumeMount().name("operator-keystore").mountPath(OPERATOR_KEYSTORE_MOUNT_PATH);
                cassandraContainer.addVolumeMountsItem(opKeystoreVolMount);
                commitlogInitContainer.addVolumeMountsItem(opKeystoreVolMount);
                sidecarContainer.addVolumeMountsItem(opKeystoreVolMount);

                podSpec.addVolumesItem(new V1Volume().name("operator-keystore")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.keystoreSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath().key("keystore.p12").path(OPERATOR_KEYSTORE))));

                V1VolumeMount opTruststoreVolMount = new V1VolumeMount().name("operator-truststore").mountPath(authorityManager.getPublicCaMountPath());
                cassandraContainer.addVolumeMountsItem(opTruststoreVolMount);
                commitlogInitContainer.addVolumeMountsItem(opTruststoreVolMount);
                sidecarContainer.addVolumeMountsItem(opTruststoreVolMount);
                podSpec.addVolumesItem(new V1Volume().name("operator-truststore")
                        .secret(new V1SecretVolumeSource()
                                .secretName(authorityManager.getPublicCaSecretName())
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_CACERT_PEM).path(AuthorityManager.SECRET_CACERT_PEM))
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_TRUSTSTORE_P12).path(AuthorityManager.SECRET_TRUSTSTORE_P12))));
            }

            // Cluster secret mounted as config file (e.g AAA shared secret)
            if (dataCenterSpec.getEnterprise() != null && dataCenterSpec.getEnterprise().getAaa() != null && dataCenterSpec.getEnterprise().getAaa().getEnabled()) {
                final String opClusterSecretPath = "/tmp/operator-cluster-secret";
                V1VolumeMount opClusterSecretVolMount = new V1VolumeMount().name("operator-cluster-secret").mountPath(opClusterSecretPath);

                cassandraContainer.addVolumeMountsItem(opClusterSecretVolMount);
                commitlogInitContainer.addVolumeMountsItem(opClusterSecretVolMount);

                podSpec.addVolumesItem(new V1Volume().name("operator-cluster-secret")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.clusterSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath().key(KEY_SHARED_SECRET).path("elasticsearch.yml.d/003-shared-secret.yaml"))));

                cassandraContainer.addArgsItem(opClusterSecretPath);
                commitlogInitContainer.addArgsItem(opClusterSecretPath);
            }

            final Restore restoreFromBackup = dataCenterSpec.getRestoreFromBackup();
            if (restoreFromBackup != null && StringUtils.isNotEmpty(restoreFromBackup.getSnapshotTag())) {
                logger.debug("Restore requested.");
                V1Container restoreInitContainer = new V1Container()
                        .name("sidecar-restore")
                        .terminationMessagePolicy("FallbackToLogsOnError")
                        .env(dataCenterSpec.getEnv())
                        .image(dataCenterSpec.getSidecarImage())
                        .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                        .securityContext(new V1SecurityContext().runAsUser(CASSANDRA_USER_ID).runAsGroup(CASSANDRA_GROUP_ID))
                        .command(ImmutableList.of(
                                "java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-XX:MaxRAMFraction=2",
                                "-cp", "/app/resources:/app/classes:/app/libs/*",
                                "com.strapdata.strapkop.sidecar.SidecarRestore",
                                "-bb", restoreFromBackup.getBucket(), // bucket name
                                "-c", dataCenterMetadata.getName(), // clusterID == DcName. Backup dc and restore dc must have the same name
                                "-bi", OperatorNames.stsName(dataCenter, rack), // pod name prefix
                                "-s",  restoreFromBackup.getSnapshotTag(), // backup tag used to find the manifest file
                                "--bs", restoreFromBackup.getProvider().name(),
                                "-rs",
                                "--shared-path", "/tmp", // elassandra can't run as root,
                                "--cd", "/tmp/sidecar-config-volume" // location where the restore task can write config fragments
                        ))
                        .addVolumeMountsItem(new V1VolumeMount()
                                .name("pod-info")
                                .mountPath("/etc/podinfo")
                        ).addVolumeMountsItem(new V1VolumeMount()
                                .name("sidecar-config-volume")
                                .mountPath("/tmp/sidecar-config-volume")
                        ).addVolumeMountsItem(new V1VolumeMount()
                                .name("data-volume")
                                .mountPath("/var/lib/cassandra")
                        );
                addBlobCredentialsForBackup(podSpec, restoreInitContainer, restoreFromBackup);
                podSpec.addInitContainersItem(restoreInitContainer);
            }

            final Map<String, String> rackLabels = OperatorLabels.rack(dataCenter, rack);

            final V1ObjectMeta templateMetadata = new V1ObjectMeta()
                    .labels(rackLabels)
                    .putAnnotationsItem(OperatorLabels.CONFIGMAP_FINGERPRINT, configMapVolumeMounts.fingerPrint());

            // add prometheus annotations to scrap nodes
            if (dataCenterSpec.getPrometheusEnabled()) {
                String[] annotations = new String[]{"prometheus.io/scrape", "true", "prometheus.io/port", "9500"};
                for (int i = 0; i < annotations.length; i += 2)
                    templateMetadata.putAnnotationsItem(annotations[i], annotations[i + 1]);
            }

            // add commitlog replayer
            podSpec.addInitContainersItem(commitlogInitContainer);

            final V1StatefulSetSpec statefulSetSpec = new V1StatefulSetSpec()
                    // if the serviceName references a headless service, kubeDNS to create an A record for
                    // each pod : $(podName).$(serviceName).$(namespace).svc.cluster.local
                    .serviceName(OperatorNames.nodesService(dataCenter))
                    .replicas(replicas)
                    .selector(new V1LabelSelector().matchLabels(rackLabels))
                    .template(new V1PodTemplateSpec()
                            .metadata(templateMetadata)
                            .spec(podSpec)
                    );
            statefulSetSpec.setVolumeClaimTemplates(getPersistentVolumeClaims(statefulSetMetadata));
            return new V1StatefulSet()
                    .metadata(statefulSetMetadata)
                    .spec(statefulSetSpec);
        }

        /**
         * Create the list of PersistenceVolumeClaims according to the DataCenterSpec if the StatefulSet doesn't exists, otherwise
         * the PersistenceVolumeClaims of the StatefulSet are preserved to avoid data lost.
         *
         * @param statefulSetMetadata
         * @return
         * @throws ApiException
         */
        private List<V1PersistentVolumeClaim> getPersistentVolumeClaims(V1ObjectMeta statefulSetMetadata) throws ApiException {
            // if the Statefulset already exists, do not override the VolumeClaims
            try {
                final V1StatefulSet statefulSet = k8sResourceUtils.readNamespacedStatefulSet(dataCenterMetadata.getNamespace(), statefulSetMetadata.getName()).blockingGet();
                logger.info("StatefulSet '{}' already exists in namespace '{}', do not modify the VolumeClaims",statefulSetMetadata.getName(), dataCenterMetadata.getNamespace());
                return statefulSet.getSpec().getVolumeClaimTemplates();
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof ApiException) && ((ApiException)e.getCause()).getCode() != 404) {
                    // rethrow the RuntimeException
                    throw e;
                }
                logger.trace("StatefulSet '{}' doesn't exists in namespace '{}', use the volume claims defined in the DatacenterSpec",statefulSetMetadata.getName(), dataCenterMetadata.getNamespace());
                return Arrays.asList(new V1PersistentVolumeClaim()
                        .metadata(new V1ObjectMeta().name("data-volume"))
                        .spec(dataCenterSpec.getDataVolumeClaim()));
            }
        }

        private void addBlobCredentialsForBackup(V1PodSpec podSpec, V1Container container, Restore restoreFrom) {
            k8sResourceUtils.readAndValidateStorageSecret(dataCenterMetadata.getNamespace(), restoreFrom.getSecretRef(), restoreFrom.getProvider());
            switch (restoreFrom.getProvider()) {
                case AZURE_BLOB:
                    addAzureBlobCredentiaksForBackup(container, restoreFrom.getSecretRef());
                    break;
                case GCP_BLOB:
                    addGCPBlobCredentialsForBackup(podSpec, container, restoreFrom.getSecretRef());
                    break;
                case AWS_S3:
                    addAWSBlobCredentialsForBackup(container, restoreFrom.getSecretRef());
                    break;
            }
        }

        private void addDnsAzureServicePrincipal(V1Container container, String secretName) {
            container.addEnvItem(buildEnvVarFromSecret("DNS_AZURE_SUBSCRIPTION_ID", DnsUpdateSecretsKeys.AZURE_SUBSCRIPTION_ID, secretName))
                    .addEnvItem(buildEnvVarFromSecret("DNS_AZURE_RESOURCE_GROUP",  DnsUpdateSecretsKeys.AZURE_RESOURCE_GROUP, secretName))
                    .addEnvItem(buildEnvVarFromSecret("DNS_AZURE_CLIENT_ID",  DnsUpdateSecretsKeys.AZURE_CLIENT_ID, secretName))
                    .addEnvItem(buildEnvVarFromSecret("DNS_AZURE_CLIENT_SECRET",  DnsUpdateSecretsKeys.AZURE_CLIENT_SECRET, secretName))
                    .addEnvItem(buildEnvVarFromSecret("DNS_AZURE_TENANT_ID",  DnsUpdateSecretsKeys.AZURE_TENANT_ID, secretName));
        }

        private void addAzureBlobCredentiaksForBackup(V1Container container, String secretName) {
            container.addEnvItem(buildEnvVarFromSecret("AZURE_STORAGE_ACCOUNT", CloudStorageSecretsKeys.AZURE_STORAGE_ACCOUNT_NAME, secretName))
                    .addEnvItem(buildEnvVarFromSecret("AZURE_STORAGE_KEY",  CloudStorageSecretsKeys.AZURE_STORAGE_ACCOUNT_KEY, secretName));
        }

        private void addAWSBlobCredentialsForBackup(V1Container container, String secretName) {
            container.addEnvItem(buildEnvVarFromSecret("AWS_REGION", CloudStorageSecretsKeys.AWS_ACCESS_KEY_REGION, secretName))
                    .addEnvItem(buildEnvVarFromSecret("AWS_ACCESS_KEY_ID",  CloudStorageSecretsKeys.AWS_ACCESS_KEY_ID, secretName))
                    .addEnvItem(buildEnvVarFromSecret("AWS_SECRET_ACCESS_KEY", CloudStorageSecretsKeys.AWS_ACCESS_KEY_SECRET, secretName));
        }

        private void addGCPBlobCredentialsForBackup(V1PodSpec podSpec, V1Container container, String secretName) {
            final String volumeName = container.getName() + "gcp-secret-volume";
            podSpec.addVolumesItem(new V1Volume()
                    .name(volumeName)
                    .secret(new V1SecretVolumeSource()
                            .secretName(secretName)
                            .addItemsItem(new V1KeyToPath()
                                    .key( CloudStorageSecretsKeys.GCP_JSON).path("gcp.json").mode(256)
                            )
                    ));
            container
                    .addVolumeMountsItem(new V1VolumeMount()
                            .readOnly(true)
                            .name(volumeName)
                            .mountPath("/tmp/" + container.getName() + "/"))
                    .addEnvItem(new V1EnvVar()
                            .name("GOOGLE_APPLICATION_CREDENTIALS")
                            .value("/tmp/" + container.getName() + "/gcp.json"))
                    .addEnvItem(buildEnvVarFromSecret("GOOGLE_CLOUD_PROJECT",  CloudStorageSecretsKeys.GCP_PROJECT_ID, secretName));
        }

        private V1EnvVar buildEnvVarFromSecret(String varName, String secretEntry, String k8sSecretReference) {
            return new V1EnvVar()
                    .name(varName)
                    .valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector()
                            .name(k8sSecretReference)
                            .key(secretEntry)));
        }

        private V1Container buildElassandraContainer(UUID seedHostId) {
            final V1Container cassandraContainer = buildElassandraBaseContainer("elassandra", seedHostId)
                    .readinessProbe(new V1Probe()
                            .exec(new V1ExecAction()
                                    .addCommandItem("/ready-probe.sh")
                                    .addCommandItem(dataCenterSpec.getNativePort().toString())
                                    .addCommandItem("9200")
                            )
                            .initialDelaySeconds(15)
                            .timeoutSeconds(5)
                    );
            if (dataCenterSpec.getElasticsearchEnabled() &&  dataCenterSpec.getEnterprise().getJmx()) {
                cassandraContainer.lifecycle(new V1Lifecycle().preStop(new V1Handler().exec(new V1ExecAction()
                        .addCommandItem("curl")
                        .addCommandItem("-X")
                        .addCommandItem("POST")
                        .addCommandItem("http://localhost:8080/enterprise/search/disable"))));
            }
            return cassandraContainer;
        }

        private V1Container buildInitContainerCommitlogReplayer(UUID seedHostId) {
            return  buildElassandraBaseContainer("commitlog-replayer", seedHostId)
                    .addEnvItem(new V1EnvVar()
                            .name("STOP_AFTER_COMMILOG_REPLAY")
                            .value("true"));
        }

        private V1Container buildElassandraBaseContainer(String containerName, UUID seedHostId) {
            final V1Container cassandraContainer = new V1Container()
                    .name(containerName)
                    .image(dataCenterSpec.getElassandraImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .securityContext(new V1SecurityContext()
                            .runAsUser(CASSANDRA_USER_ID)
                            .capabilities(new V1Capabilities().add(ImmutableList.of(
                                    "IPC_LOCK",
                                    "SYS_RESOURCE"
                            ))))
                    .resources(dataCenterSpec.getResources())
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("data-volume")
                            .mountPath("/var/lib/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("nodeinfo")
                            .mountPath("/nodeinfo")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("pod-info")
                            .mountPath("/etc/podinfo")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("sidecar-config-volume")
                            .mountPath("/tmp/sidecar-config-volume")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("cassandra-log-volume")
                            .mountPath("/var/log/cassandra")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("cqlshrc-volume")
                            .mountPath("/home/cassandra/.cassandra/cqlshrc")
                            .subPath("cqlshrc")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("curlrc-volume")
                            .mountPath("/home/cassandra/.curlrc")
                            .subPath(".curlrc")
                    )
                    .addArgsItem("/tmp/sidecar-config-volume")
                    .addEnvItem(new V1EnvVar().name("JMX_PORT").value(Integer.toString(dataCenterSpec.getJmxPort())))
                    .addEnvItem(new V1EnvVar().name("CQLS_OPTS").value( dataCenterSpec.getSsl() ? "--ssl" : ""))
                    .addEnvItem(new V1EnvVar().name("ES_SCHEME").value( dataCenterSpec.getEnterprise().getHttps() ? "https" : "http"))
                    .addEnvItem(new V1EnvVar().name("HOST_NETWORK").value( Boolean.toString(dataCenterSpec.getHostNetworkEnabled())))
                    .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                    .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("SEED_HOST_ID").value(seedHostId.toString()))
                    ;

            String nodetoolOpts = " -u cassandra -pwf /etc/cassandra/jmxremote.password ";
            nodetoolOpts += dataCenterSpec.getJmxmpEnabled() ? " --jmxmp " : "";
            nodetoolOpts += useJmxOverSSL() ? " --ssl " : "";
            cassandraContainer.addEnvItem(new V1EnvVar().name("NODETOOL_OPTS").value(nodetoolOpts));

            if (dataCenterSpec.getSsl()) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name("nodetool-ssl-volume")
                        .mountPath("/home/cassandra/.cassandra/nodetool-ssl.properties")
                        .subPath("nodetool-ssl.properties")
                );
            }

            if (dataCenterSpec.getHostPortEnabled()) {
                // expose only one storage port on node
                if (dataCenterSpec.getSsl()) {
                    addPortsItem(cassandraContainer, dataCenterSpec.getSslStoragePort(), "internode-ssl", true);
                } else {
                    addPortsItem(cassandraContainer, dataCenterSpec.getStoragePort(), "internode", true);
                }
            } else {
                addPortsItem(cassandraContainer, dataCenterSpec.getStoragePort(), "internode", false);
                addPortsItem(cassandraContainer, dataCenterSpec.getSslStoragePort(), "internode-ssl", false);
            }
            addPortsItem(cassandraContainer, dataCenterSpec.getNativePort(), "cql", dataCenterSpec.getHostPortEnabled());
            addPortsItem(cassandraContainer, dataCenterSpec.getJmxPort(), "jmx", false);
            addPortsItem(cassandraContainer, dataCenterSpec.getJdbPort(), "jdb", false);

            if (dataCenterSpec.getElasticsearchEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name("elasticsearch").containerPort(9200));
                cassandraContainer.addPortsItem(new V1ContainerPort().name("transport").containerPort(9300));
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.ElassandraDaemon"));
            } else {
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.CassandraDaemon"));
            }

            return cassandraContainer;
        }

        private V1Container buildInitContainerVmMaxMapCount() {
            return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                    .name("increase-vm-max-map-count")
                    .image("busybox")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sysctl", "-w", "vm.max_map_count=1048575"));
        }

        private V1Container buildInitContainerMergeTrustCerts() {
            return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(false))
                    .name("merge-trust-certs")
                    .image("openjdk:alpine")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sh","-c",
                                    "cp $JAVA_HOME/jre/lib/security/cacerts /tmp/sidecar-truststore/ && " +
                                    String.format(Locale.ROOT, "keytool -import -trustcacerts -keystore /tmp/sidecar-truststore/cacerts -storepass changeit -alias strapkop -noprompt -file %s/cacert.pem",
                                            authorityManager.getPublicCaMountPath())
                    ))
                    .addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath(authorityManager.getPublicCaMountPath()))
                    .addVolumeMountsItem(new V1VolumeMount().name("sidecar-truststore-volume").mountPath("/tmp/sidecar-truststore"));
        }

        // Nodeinfo init container if NODEINFO_SECRET is available as env var
        private V1Container buildInitContainerNodeInfo(String nodeInfoSecretName) {
            return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                    .name("nodeinfo")
                    .image("bitnami/kubectl")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sh", "-c",
                            "kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"failure-domain.beta.kubernetes.io/zone\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/zone " +
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"beta.kubernetes.io/instance-type\"}}'| awk '!/<no value>/ { print $0 }' > /nodeinfo/instance-type " +
                                    " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"storagetier\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/storagetier " +
                                    ((dataCenterSpec.getHostPortEnabled()) ? " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o go-template='{{index .metadata.labels \"kubernetes.strapdata.com/public-ip\"}}' | awk '!/<no value>/ { print $0 }' > /nodeinfo/public-ip " : "") +
                                    ((dataCenterSpec.getHostPortEnabled()) ? " && kubectl get no ${NODE_NAME} --token=\"$NODEINFO_TOKEN\" -o jsonpath='{.status.addresses[?(@.type==\"InternalIP\")].address}' > /nodeinfo/node-ip " : "") +
                                    " && grep ^ /nodeinfo/*"
                    ))
                    .addVolumeMountsItem(new V1VolumeMount()
                            .name("nodeinfo")
                            .mountPath("/nodeinfo")
                    )
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("NODEINFO_TOKEN").valueFrom(new V1EnvVarSource().secretKeyRef(new V1SecretKeySelector().name(nodeInfoSecretName).key("token"))));
        }

    }

    private boolean useJmxOverSSL() {
        return dataCenterSpec.getSsl() && (!dataCenterSpec.getJmxmpEnabled() || (dataCenterSpec.getJmxmpEnabled() && dataCenterSpec.getJmxmpOverSSL()));
    }

    /**
     * This class holds information about a kubernetes zone (which is an elassandra rack)
     */
    @Data
    public static class Zone {
        final String name;
        Integer size = 0;   // number of nodes in the zone
        Optional<V1StatefulSet> sts;  // attached statefulset

        public Zone(final String name) {
            this.name = name;
        }

        // this scaleComparator returns zone where to add next replicas first
        private static final Comparator<Zone> scaleComparator = Comparator
                // smallest replicas first
                .comparingInt(Zone::getSize)
                // in case there is equality, pick the zone with the most slots available
                .thenComparingInt(z -> -z.freeNodeCount())
                // finally use lexical order of the zone name
                .thenComparing(Zone::getName);


        public int replicas() {
            return (!sts.isPresent()) ? 0 : Optional.ofNullable(sts.get().getStatus().getReplicas()).orElse(0);
        }

        public int currentReplicas() { return (!sts.isPresent()) ? 0 :  Optional.ofNullable(sts.get().getStatus().getCurrentReplicas()).orElse(0); }

        public int readyReplicas() { return (!sts.isPresent()) ? 0 : Optional.ofNullable(sts.get().getStatus().getReadyReplicas()).orElse(0); }

        public int freeNodeCount() {
            return size - replicas();
        }

        public Optional<String> getDataCenterFingerPrint() {
            Optional<String> result = Optional.empty();
            if (sts.isPresent()) {
                V1ObjectMeta metadata = sts.get().getMetadata();
                if (metadata.getAnnotations() != null) {
                    result = Optional.ofNullable(metadata.getAnnotations().get(OperatorLabels.DATACENTER_FINGERPRINT));
                }
            }
            return result;
        }

        public Optional<Long> getDataCenterGeneation() {
            Optional<Long> result = Optional.empty();
            if (sts.isPresent()) {
                V1ObjectMeta metadata = sts.get().getMetadata();
                if (metadata.getAnnotations() != null) {
                    result = Optional.ofNullable(metadata.getAnnotations().get(OperatorLabels.DATACENTER_GENERATION)).map(Long::valueOf);
                }
            }
            return result;
        }

        public boolean isReady() {
            if (!sts.isPresent())
                return true;
            final V1StatefulSetStatus status = this.sts.get().getStatus();
            return  status != null &&
                    sts.get().getSpec().getReplicas() == status.getReadyReplicas() &&
                    ( Strings.isNullOrEmpty(status.getUpdateRevision()) ||
                            Objects.equals(status.getUpdateRevision(), status.getCurrentRevision()));
        }

        public boolean isUpdating() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return (status.getUpdateRevision() != null &&
                    status.getUpdateRevision() != status.getCurrentRevision() &&
                    Optional.ofNullable(status.getUpdatedReplicas()).orElse(0) < status.getReplicas());
        }

        public boolean isScalingUp() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() < sts.get().getSpec().getReplicas();
        }

        public boolean isScalingDown() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() > sts.get().getSpec().getReplicas();
        }

        public boolean isParked() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() == sts.get().getSpec().getReplicas() &&
                    status.getReplicas() == 0
                    && Optional.ofNullable(status.getCurrentReplicas()).orElse(0) == 0;
        }

        public ElassandraPod lastPod(DataCenter dataCenter) {
            return new ElassandraPod(dataCenter, name, (size -1));
        }

        public ElassandraPod firstPod(DataCenter dataCenter) {
            return new ElassandraPod(dataCenter, name, 0);
        }

        public ElassandraPod pod(DataCenter dataCenter, int ordinal) {
            return new ElassandraPod(dataCenter, name, ordinal);
        }

        public List<ElassandraPod> pods(DataCenter dataCenter) {
            List<ElassandraPod> pods = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                pods.add(new ElassandraPod(dataCenter, name, i));
            }
            return pods;
        }
    }

    public static class Zones implements Iterable<Zone> {
        Map<String, Zone> zoneMap = new LinkedHashMap<>();

        public Zones(CoreV1Api coreApi, Map<String, V1StatefulSet> existingStatefulSetsByZone) throws ApiException {
            this(coreApi.listNode(false, null, null, null, null,
                    null, null, null, null).getItems(), existingStatefulSetsByZone);
        }

        public Zones(List<V1Node> nodes, Map<String, V1StatefulSet> existingStatefulSetsByZone) {
            for(V1Node node : nodes) {
                String zoneName = node.getMetadata().getLabels().get(OperatorLabels.ZONE);
                if (zoneName == null) {
                    throw new RuntimeException(new StrapkopException(String.format("missing label %s on node %s", OperatorLabels.ZONE, node.getMetadata().getName())));
                }
                zoneMap.compute(zoneName, (k, z) -> {
                    if (z == null) {
                        z = new Zone(zoneName);
                    }
                    z.size++;
                    z.setSts(Optional.ofNullable(existingStatefulSetsByZone.get(zoneName)));
                    return z;
                });
            }
        }

        public boolean parkedDatacenter() {
            return totalCurrentReplicas() == 0;
        }

        public int totalNodes() {
            return zoneMap.values().stream().map(z -> z.size).reduce(0, Integer::sum);
        }

        public int totalReplicas() {
            return zoneMap.values().stream().map(Zone::replicas).reduce(0, Integer::sum);
        }

        public int totalCurrentReplicas() {
            return zoneMap.values().stream().map(Zone::currentReplicas).reduce(0, Integer::sum);
        }

        public int totalReadyReplicas() {
            return zoneMap.values().stream().map(Zone::readyReplicas).reduce(0, Integer::sum);
        }

        public Optional<Zone> nextToScalueUp() {
            return (totalNodes() == totalReplicas()) ? Optional.empty() : zoneMap.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.freeNodeCount() > 0)
                    // select the preferred zone based on some priorities
                    .min(Zone.scaleComparator);
        }

        public Optional<Zone> nextToScaleDown() {
            return (totalReplicas() == 0) ? Optional.empty() : zoneMap.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.replicas() > 0 || !z.sts.isPresent())
                    // select the preferred zone based on some priorities
                    .max(Zone.scaleComparator);
        }

        public Optional<Zone> first() {
            return zoneMap.values().stream().min(Zone.scaleComparator);
        }

        public boolean isReady() {
            return zoneMap.values().stream().allMatch(Zone::isReady);
        }

        public boolean hasConsistentConfiguration() {
            return zoneMap.values().stream()
                    .map(z -> z.getDataCenterFingerPrint())
                    .filter(Optional::isPresent).collect(Collectors.toSet()).size() == 1;
        }

        /**
         * Returns an iterator over elements of type {@code T}.
         *
         * @return an Iterator.
         */
        @Override
        public Iterator<Zone> iterator() {
            return zoneMap.values().iterator();
        }
    }

    public Completable updateRack(Zones zones, V1StatefulSet v1StatefulSet, String rack, Map<String, RackStatus> rackStatusMap) throws ApiException {
        logger.debug("DataCenter={} in namespace={} phase={} UPDATING config of rack={}",
                dataCenterMetadata.getName(), rack, dataCenterMetadata.getNamespace(),
                dataCenterStatus.getPhase(), rack);
        updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusMap);
        prepareDataCenterSnapshot(dataCenterStatus.getPhase(), v1StatefulSet);
        return replaceNamespacedStatefulSet(v1StatefulSet);
    }

    public Completable parkRack(Zones zones, V1StatefulSet v1StatefulSet, String rack, Map<String, RackStatus> rackStatusMap) throws ApiException {
        logger.debug("DataCenter={} in namespace={} phase={} PARKING {} pods of rack={}",
                dataCenterMetadata.getName(), rack, dataCenterMetadata.getNamespace(),
                dataCenterStatus.getPhase(), v1StatefulSet.getSpec().getReplicas(), rack);
        rackStatusMap.get(rack).setParkedReplicas(v1StatefulSet.getSpec().getReplicas());
        v1StatefulSet.getSpec().setReplicas(0);
        rackStatusMap.get(rack).setPhase(RackPhase.PARKING);
        updateDatacenterStatus(DataCenterPhase.PARKING, zones, rackStatusMap);
        return replaceNamespacedStatefulSet(v1StatefulSet);
    }

    public Completable unparkRack(Zones zones, V1StatefulSet v1StatefulSet, String rack, Map<String, RackStatus> rackStatusMap) throws ApiException {
        final int podsToRestore = rackStatusMap.get(rack).getParkedReplicas();
        logger.debug("DataCenter={} in namespace={} phase={} UPDATING {} pods of rack={}",
                dataCenterMetadata.getName(), rack, dataCenterMetadata.getNamespace(),
                dataCenterStatus.getPhase(), podsToRestore, rack);
        rackStatusMap.get(rack).setParkedReplicas(0);
        rackStatusMap.get(rack).setPhase(RackPhase.UPDATING);
        v1StatefulSet.getSpec().setReplicas(podsToRestore);
        updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusMap);
        return replaceNamespacedStatefulSet(v1StatefulSet);
    }

    private void updateDatacenterStatus(DataCenterPhase dcPhase, Zones zones, Map<String, RackStatus> rackStatusMap) {
        updateDatacenterStatus(dcPhase, zones, rackStatusMap, Optional.empty());
    }

    private void updateDatacenterStatus(DataCenterPhase dcPhase, Zones zones, Map<String, RackStatus> rackStatusMap, Optional<String> lastMeassage) {
        updateDatacenterStatus(dataCenterStatus, dcPhase, zones, rackStatusMap, lastMeassage);
    }

    private void updateDatacenterStatus(DataCenterStatus status, DataCenterPhase dcPhase, Zones zones, Map<String, RackStatus> rackStatusMap, Optional<String> lastMeassage) {
        final Map<String, ElassandraNodeStatus> podStatuses = new HashMap<>();

        // update pod
        for(Zone zone : zones) {
            for (int i = 0; i < zone.size; i++) {
                ElassandraPod pod = new ElassandraPod(dataCenter, zone.name, i);
                podStatuses.put(pod.getName(), elassandraNodeStatusCache.getOrDefault(pod, ElassandraNodeStatus.UNKNOWN));
            }
        }
        status.setRackStatuses(rackStatusMap);
        status.setElassandraNodeStatuses(podStatuses);

        // update dc status
        status.setPhase(dcPhase);
        status.setReplicas(zones.totalReplicas());
        status.setJoinedReplicas(rackStatusMap.values().stream().collect(Collectors.summingInt(RackStatus::getJoinedReplicas)));
        status.setReadyReplicas(zones.totalReadyReplicas());
        lastMeassage.ifPresent((msg) -> status.setLastMessage(msg));
    }

    /**
     * Enumerate the list of pods name based on existing statefulsets and .spec.replicas
     * This does not execute any network operation
     */
    private List<ElassandraPod> enumeratePods(TreeMap<String, V1StatefulSet> existingStsMap) {
        List<ElassandraPod> pods = new ArrayList<>();
        for (Map.Entry<String, V1StatefulSet> entry : existingStsMap.entrySet()) {
            String rack = entry.getKey();
            V1StatefulSet sts = entry.getValue();
            for (int i = 0; i < sts.getSpec().getReplicas(); i++) {
                pods.add(new ElassandraPod(dataCenter, rack, i));
            }
        }
        return pods;
    }

    private static String toYamlString(final Object object) {
        final DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(object);
    }
}
