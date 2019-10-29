package com.strapdata.strapkop.reconcilier;

import com.google.api.client.util.Lists;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.*;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import lombok.Data;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    
    private final CqlConnectionManager cqlConnectionManager;
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    public final Builder builder = new Builder();

    public DataCenterUpdateAction(final ApplicationContext context,
                                  final CoreV1Api coreApi,
                                  final AppsV1Api appsApi,
                                  final CustomObjectsApi customObjectsApi,
                                  final K8sResourceUtils k8sResourceUtils,
                                  final AuthorityManager authorityManager,
                                  final CqlConnectionManager cqlConnectionManager,
                                  final ElassandraNodeStatusCache elassandraNodeStatusCache,
                                  final SidecarClientFactory sidecarClientFactory,
                                  @Parameter("dataCenter") com.strapdata.model.k8s.cassandra.DataCenter dataCenter) {
        this.context = context;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        
        this.dataCenter = dataCenter;
        this.dataCenterMetadata = dataCenter.getMetadata();
        this.dataCenterSpec = dataCenter.getSpec();

        this.cqlConnectionManager = cqlConnectionManager;
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
                .flatMap(clusterSecret -> {
                    // create cluster secret if not exists
                    Map<String, String> passwords = new HashMap<>();
                    passwords.put("cassandra.cassandra_password", UUID.randomUUID().toString());
                    passwords.put("cassandra.strapkop_password", UUID.randomUUID().toString());
                    passwords.put("cassandra.admin_password", UUID.randomUUID().toString());
                    passwords.put("cassandra.jmx_password", UUID.randomUUID().toString());
                    passwords.put("shared-secret.yaml", "aaa.shared_secret: " + UUID.randomUUID().toString());
                    passwords.put("cassandra.reaper_password", UUID.randomUUID().toString());
                    return k8sResourceUtils.getOrCreateNamespacedSecret(clusterSecret, () -> {
                                V1Secret secret = new V1Secret().metadata(clusterSecret);
                                for(Map.Entry<String,String> entry : passwords.entrySet())
                                    secret.putStringDataItem(entry.getKey(), entry.getValue());
                                return secret;
                            }
                    ).map(s -> passwords);
                })
                .flatMap(passwords -> {
                    // create rc file
                    return k8sResourceUtils.getOrCreateNamespacedSecret(builder.clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter)),
                            () -> builder.buildSecretRcFile("admin", passwords.get("cassandra.admin_password")))
                            .map(s -> {
                                passwords.clear();
                                return passwords;
                            });
                })
                .flatMap(passwords -> {
                    // update elassandra keystores
                    return (dataCenterSpec.getSsl()) ?
                            k8sResourceUtils.getOrCreateNamespacedSecret(builder.dataCenterObjectMeta(OperatorNames.keystore(dataCenter)),
                                    () -> builder.buildSecretKeystore()).map(s2 -> passwords) :
                            Single.just(passwords);
                })
                .flatMap(s4 -> fetchExistingStatefulSetsByZone())
                .flatMapCompletable(existingStsMap -> {
                    Zones zones = new Zones(this.coreApi, existingStsMap);

                    // 1.lookup for evolving rack
                    final Map<String, RackStatus> rackStatusByName = new HashMap<>();
                    RackStatus movingRack = null;
                    for(RackStatus rackStatus : dataCenterStatus.getRackStatuses()) {
                        rackStatusByName.put(rackStatus.getName(), rackStatus);
                        if (!rackStatus.getPhase().equals(RackPhase.RUNNING)) {
                            if (movingRack != null)
                                logger.error("Found more than one moving rack=[{},{}]", movingRack.getName(), rackStatus.getName());
                            movingRack = rackStatus;
                        }
                    }

                    ElassandraPod failedPod = null;
                    if (movingRack != null) {
                        Zone movingZone = zones.zones.get(movingRack.getName());
                        logger.debug("movinRack={} phase={} isReady={} isUpdating={} isScalingUp={} isScalingDown={} firstPodStatus={} lastPodStatus={}",
                                movingRack.getName(), movingRack.getPhase(), movingZone.isReady(), movingZone.isScalingUp(), movingZone.isScalingDown(),
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
                                break;
                            case UPDATING:
                                // rolling update done and first node NORMAL
                                if (!movingZone.isUpdating() && elassandraNodeStatusCache.isNormal(movingZone.firstPod(dataCenter))) {
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("First node NORMAL after rolling UPDATE in rack={} size={}", movingZone.name, movingZone.size);
                                }
                                break;
                            case SCALING_UP:
                                // scale up done and last node NORMAL
                                ElassandraPod lastNode = movingZone.lastPod(dataCenter);
                                if (!movingZone.isScalingUp() && elassandraNodeStatusCache.isNormal(movingZone.lastPod(dataCenter))) {
                                    movingRack.setJoinedReplicas(movingZone.size);
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("Last node NORMAL after SCALE_UP in rack={} size={}", movingZone.name, movingZone.size);
                                }
                                break;
                            case SCALING_DOWN:
                                // scale down
                                if (!movingZone.isScalingDown()) {
                                    movingRack.setJoinedReplicas(movingZone.size);
                                    movingRack.setPhase(RackPhase.RUNNING);
                                    updateDatacenterStatus(DataCenterPhase.RUNNING, zones, rackStatusByName);
                                    logger.debug("SCALE_DOWN done in rack={} size={}", movingZone.name, movingZone.size);
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
                            case FAILED:
                                break;
                            default:
                                logger.debug("Waiting ongoing operation phase={} rack={} size={}", movingRack.getPhase(), movingZone.name, movingZone.size);
                                return Completable.complete();
                        }
                    }

                    // check all existing pod are UP and NORMAL before starting a new operation
                    for (ElassandraPod pod : enumeratePods(existingStsMap)) {
                        ElassandraNodeStatus podStatus = Optional
                                .ofNullable(elassandraNodeStatusCache.get(pod))
                                .orElse(ElassandraNodeStatus.UNKNOWN);
                        switch(podStatus) {
                            case NORMAL:
                            case FAILED:
                                break;
                            default:
                                logger.info("Pod name={} status={}, delaying operation.", pod.getName(), podStatus);
                                return Completable.complete();
                        }
                    }

                    // look up for the next rack to update if needed.
                    // if a pod is failed, only update the config in the same rack to avoid a general outage !
                    for (V1StatefulSet v1StatefulSet : existingStsMap.values()) {
                        String stsFingerprint = v1StatefulSet.getSpec().getTemplate().getMetadata().getAnnotations().get("configmap-fingerprint");
                        String rack = v1StatefulSet.getSpec().getTemplate().getMetadata().getLabels().get("rack");
                        ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, rack);
                        String configFingerprint = configMapVolumeMounts.fingerPrint();
                        if (!configFingerprint.equals(stsFingerprint)) {
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
                                rackStatusByName.get(rack).setPhase(RackPhase.UPDATING);
                                updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusByName);
                                return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps()
                                        .andThen(updateRack(zones, builder.buildStatefulSetRack(rack, replicas, configMapVolumeMounts), rack, rackStatusByName));
                            }
                        }
                    }

                    if (failedPod != null) {
                        logger.info("pod={} FAILED, cannot scale the datacenter now", failedPod);
                        return Completable.complete();
                    }

                    if (zones.totalReplicas() < dataCenter.getSpec().getReplicas()) {
                        Optional<Zone> scaleUpZone = zones.nextToScalueUp();
                        if (scaleUpZone.isPresent()) {
                            Zone zone = scaleUpZone.get();
                            if (!zone.getSts().isPresent()) {
                                // create new sts with replicas = 1,
                                rackStatusByName.put(zone.name, new RackStatus().setName(zone.name).setPhase(RackPhase.CREATING));
                                final V1StatefulSet sts = builder.buildStatefulSetRack(zones, zone.getName(), 1);
                                updateDatacenterStatus(DataCenterPhase.SCALING_UP, zones, rackStatusByName);
                                logger.debug("SCALE_UP started in rack={} size={}", zone.name, zone.size);
                                return k8sResourceUtils
                                        .createOrReplaceNamespacedService(builder.buildServiceSeed(zone.getName()))
                                        .flatMapCompletable(s -> {
                                            ConfigMapVolumeMounts configMapVolumeMounts = new ConfigMapVolumeMounts(zones, zone.name);
                                            return configMapVolumeMounts.createOrReplaceNamespacedConfigMaps();
                                        })
                                        .andThen(k8sResourceUtils.createNamespacedStatefulSet(sts).ignoreElement());
                            }
                            // +1 on sts replicas
                            V1StatefulSet sts = zone.getSts().get();
                            sts.getSpec().setReplicas(sts.getSpec().getReplicas() + 1);
                            dataCenterStatus.setNeedCleanup(true);
                            rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_UP);
                            updateDatacenterStatus(DataCenterPhase.SCALING_UP, zones, rackStatusByName);
                            logger.debug("SCALE_UP started in rack={} size={}", zone.name, zone.size);
                            return k8sResourceUtils.replaceNamespacedStatefulSet(sts).ignoreElement();
                        }
                        logger.warn("Cannot scale up, no free node in datacenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
                    } else if (zones.totalReplicas() > dataCenter.getSpec().getReplicas()) {
                        Optional<Zone> scaleDownZone = zones.nextToScaleDown();
                        if (scaleDownZone.isPresent()) {
                            Zone zone = scaleDownZone.get();
                            V1StatefulSet sts = zone.getSts().get();
                            ElassandraPod elassandraPod = zone.lastPod(dataCenter);
                            ElassandraNodeStatus elassandraNodeStatus = elassandraNodeStatusCache.getOrDefault(elassandraPod, ElassandraNodeStatus.UNKNOWN);
                            // UNKNOWN, STARTING, NORMAL, JOINING, LEAVING, DECOMMISSIONED, MOVING, DRAINING, DRAINED, DOWN;
                            switch(elassandraNodeStatus) {
                                case NORMAL:
                                    // blocking call to decommission, max 5 times, with 2 second delays between each try
                                    rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_DOWN);
                                    updateDatacenterStatus(DataCenterPhase.SCALING_DOWN, zones, rackStatusByName);
                                    logger.debug("SCALE_DOWN started in rack={} size={}, decommissioning pod={} status={}",
                                            zone.name, zone.size, elassandraPod, elassandraNodeStatus);
                                    return sidecarClientFactory.clientForPod(elassandraPod).decommission()
                                            .retryWhen(errors -> errors
                                                    .zipWith(Flowable.range(1, 5), (n, i) -> i)
                                                    .flatMap(retryCount -> Flowable.timer(2, TimeUnit.SECONDS))
                                            );
                                case DECOMMISSIONED:
                                case DRAINED:
                                case DOWN:
                                    sts.getSpec().setReplicas(sts.getSpec().getReplicas() - 1);
                                    logger.info("Scaling down sts={} to {}, removing pod={}",
                                            sts.getMetadata().getName(), sts.getSpec().getReplicas(), elassandraPod);
                                    rackStatusByName.get(zone.name).setPhase(RackPhase.SCALING_DOWN);
                                    updateDatacenterStatus(DataCenterPhase.SCALING_DOWN, zones, rackStatusByName);
                                    logger.debug("SCALE_DOWN started in rack={} size={}, removing pod={} status={}",
                                            zone.name, zone.size, elassandraPod, elassandraNodeStatus);
                                    return k8sResourceUtils.replaceNamespacedStatefulSet(sts).ignoreElement();
                                default:
                                    logger.info("Waiting a valid status to remove pod={} from sts={} in namspace={}",
                                            elassandraPod,sts.getMetadata().getName(), dataCenterMetadata.getNamespace());
                            }
                        }
                        logger.warn("Cannot scale down, no more replicas in datacenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
                    }
                    return Completable.complete();
                });
    }



    /**
     * Currently, only one node is used as a seed. It must be the first node.
     * Now that rack distribution is dynamic, it's hard to find a deterministic way to select the seed rack.
     * This method implements the logic to choose a stable seed rack over the datacenter lifetime.
     */
    private String selectSeedRack(Map<String, V1StatefulSet> existingStsMap, List<Zone> zones) throws StrapkopException {
        String seedRack;// ... either there is existing sts, in that case we look for the oldest one
        if (existingStsMap.size() > 0) {
            final V1StatefulSet oldestSts = Collections.min(
                    existingStsMap.values(),
                    Comparator.comparing(sts -> sts.getMetadata().getCreationTimestamp())
            );
            seedRack = oldestSts.getMetadata().getLabels().get(OperatorLabels.RACK);
        }
        // ... or if there is no sts yet, we use the zone that will be used for the first replicas
        else {
            final List<Zone> seedZoneCandidates = zones.stream().filter(z -> z.replicas() > 0).collect(Collectors.toList());
            if (seedZoneCandidates.size() != 1) {
                throw new StrapkopException(String.format("internal error, can't determine the seed node dc=%s", dataCenterMetadata.getName()));
            }
            seedRack = seedZoneCandidates.get(0).getName();
        }
        return seedRack;
    }


    public class ConfigMapVolumeMountBuilder {
        public final V1ConfigMap configMap;
        public final V1ConfigMapVolumeSource volumeSource;
        public final String mountName, mountPath;

        public ConfigMapVolumeMountBuilder(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this.configMap = configMap;
            this.volumeSource = volumeSource;
            this.mountName = mountName;
            this.mountPath = mountPath;
        }

        public ConfigMapVolumeMountBuilder makeUnique() {
            if (configMap != null) {
                String hashedName = configMap.getMetadata().getName() + "-" + fingerPrint();
                configMap.getMetadata().setName(hashedName);
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
        }

        public String fingerPrint() {
            return this.specConfig.fingerPrint();
        }

        public Completable createOrReplaceNamespacedConfigMaps() throws ApiException {
            return specConfig.createOrReplaceNamespacedConfigMap().ignoreElement()
                    .andThen(rackConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    .andThen(seedConfig.createOrReplaceNamespacedConfigMap().ignoreElement())
                    // load and make unique user configmap
                    .andThen((dataCenterSpec.getUserConfigMapVolumeSource() == null) ?
                            Completable.complete() :
                            k8sResourceUtils.getConfigMap(dataCenterMetadata.getNamespace(), dataCenterSpec.getUserConfigMapVolumeSource().getName())
                                    .flatMapCompletable(configMap -> {
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
                                        ConfigMapVolumeMountBuilder configMapVolumeMountBuilder = new ConfigMapVolumeMountBuilder(configMap1, dataCenterSpec.getUserConfigMapVolumeSource(), "user-config-volume", "/tmp/user-config");
                                        return configMapVolumeMountBuilder.makeUnique().createOrReplaceNamespacedConfigMap().ignoreElement();
                                    }));
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
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
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
                                    new V1ServicePort().name("internode").port(
                                            dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort())))
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
            for(RackStatus rackStatus : dataCenterStatus.getRackStatuses()) {
                if (rackStatus.getJoinedReplicas() > 0)
                    seeds.add(new ElassandraPod(dataCenter, rackStatus.getName(), 0).getName());
            }

            logger.debug("seeds={} remoteSeeds={} remoteSeeders={}", seeds, remoteSeeds, remoteSeeders);

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
                    "class_name", "com.strapdata.cassandra.k8s.SeedProvider",
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
        public ConfigMapVolumeMountBuilder buildConfigMapSpec() throws IOException {
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

                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-spec.yaml", toYamlString(config));
            }

            // prometheus support (see prometheus annotations)
            if (dataCenterSpec.getPrometheusEnabled()) {
                // instaclustr jmx agent
            /*
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/001-cassandra-exporter.sh",
                "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/cassandra-exporter-agent.jar=@${CASSANDRA_CONF}/cassandra-exporter.conf\"");
            */

                // jmx-promtheus exporter
                // TODO: use version less symlink to avoid issues when upgrading the image
                configMapVolumeMountBuilder.addFile( "cassandra-env.sh.d/001-cassandra-exporter.sh",
                        "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/jmx_prometheus_javaagent.jar=9500:${CASSANDRA_CONF}/jmx_prometheus_exporter.yml\"");
            }

            // Add JMX configuration
            if (dataCenterSpec.getJmxmpEnabled()) {
                // JMXMP is fine, but visualVM cannot use jmxmp+tls+auth
                configMapVolumeMountBuilder.addFile("jvm.options.d/001-jmx.options",
                        "-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort() + "\n" +
                                "-Dcassandra.jmxmp=true\n"
                );
            } else {
                // Remote JMX require SSL, otherwise this is local clear JMX
                if (dataCenterSpec.getSsl()) {
                    configMapVolumeMountBuilder.addFile("jvm.options.d/001-jmx-ssl.options",
                            "-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort() + "\n" +
                                    "-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n" +
                                    "-Dcom.sun.management.jmxremote.authenticate=true\n" +
                                    "-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n" +
                                    //"-Dcom.sun.management.jmxremote.access.file=/etc/cassandra/jmxremote.access\n" + \
                                    "-Dcom.sun.management.jmxremote.ssl=true\n" +
                                    "-Dcom.sun.management.jmxremote.registry.ssl=true\n" +
                                    "-Djavax.net.ssl.keyStore=" + OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE + "\n" +
                                    "-Djavax.net.ssl.keyStorePassword=" + OPERATOR_KEYPASS + "\n" +
                                    "-Djavax.net.ssl.keyStoreType=PKCS12\n" +
                                    "-Djavax.net.ssl.trustStore=" + authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + "\n" +
                                    "-Djavax.net.ssl.trustStorePassword=" + authorityManager.getCaTrustPass() + "\n" +
                                    "-Djavax.net.ssl.trustStoreType=PKCS12");
                } else {
                    // local JMX, clear + no auth
                    configMapVolumeMountBuilder.addFile("jvm.options.d/001-jmx.options",
                            "-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort() + "\n" +
                                    "-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n" +
                                    "-Dcom.sun.management.jmxremote.authenticate=true\n" +
                                    "-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n" +
                                    "-Djava.rmi.server.hostname=127.0.0.1\n" +
                                    "-XX:+DisableAttachMechanism");
                }
            }

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
                        .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/keystore.p12")
                        .put("keystore_password", "changeit")
                        .put("truststore", authorityManager.getPublicCaMountPath() + "/truststore.p12")
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
                        .put("keystore", "/tmp/operator-keystore/keystore.p12")
                        .put("keystore_password", "changeit")
                        .put("truststore", authorityManager.getPublicCaMountPath() + "/truststore.p12")
                        .put("truststore_password", "changeit")
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
                        "JVM_OPTS=\"$JVM_OPTS -Dcassandra.custom_query_handler_class=org.elassandra.index.EnterpriseElasticQueryHandler\"");
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

        public V1Secret buildSecretKeystore() throws Exception {
            final V1ObjectMeta certificatesMetadata = dataCenterObjectMeta(OperatorNames.keystore(dataCenter));

            // generate statefulset wildcard certificate in a PKCS12 keystore
            final String wildcardStatefulsetName = "*." + OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            final String headlessServiceName = OperatorNames.nodesService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            final String elasticsearchServiceName = OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local";
            @SuppressWarnings("UnstableApiUsage") final V1Secret certificatesSecret = new V1Secret()
                    .metadata(certificatesMetadata)
                    .putDataItem("keystore.p12",
                            authorityManager.issueCertificateKeystore(
                                    wildcardStatefulsetName,
                                    ImmutableList.of(wildcardStatefulsetName, headlessServiceName, elasticsearchServiceName, "localhost"),
                                    ImmutableList.of(InetAddresses.forString("127.0.0.1")),
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
        public V1Secret buildSecretRcFile(String username, String password) throws ApiException {
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

        public V1StatefulSet buildStatefulSetRack(Zones zones, String rack, int replicas) throws IOException, ApiException, StrapkopException {
            return buildStatefulSetRack(rack, replicas, new ConfigMapVolumeMounts(zones, rack));
        }

        public V1StatefulSet buildStatefulSetRack(final String rack, final int replicas, ConfigMapVolumeMounts configMapVolumeMounts) throws ApiException, StrapkopException {
            final V1ObjectMeta statefulSetMetadata = rackObjectMeta(rack, OperatorNames.stsName(dataCenter, rack));

            final V1Container cassandraContainer = new V1Container()
                    .name("elassandra")
                    .image(dataCenterSpec.getElassandraImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .resources(dataCenterSpec.getResources())
                    .securityContext(new V1SecurityContext()
                            .runAsUser(999L)
                            .capabilities(new V1Capabilities().add(ImmutableList.of(
                                    "IPC_LOCK",
                                    "SYS_RESOURCE"
                            ))))
                    .readinessProbe(new V1Probe()
                            .exec(new V1ExecAction()
                                    .addCommandItem("/ready-probe.sh")
                                    .addCommandItem(dataCenterSpec.getNativePort().toString())
                                    .addCommandItem("9200")
                            )
                            .initialDelaySeconds(15)
                            .timeoutSeconds(5)
                    )
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
                    .addEnvItem(new V1EnvVar().name("NODETOOL_OPTS").value(
                            dataCenterSpec.getJmxmpEnabled() ?
                                    " -Dcassandra.jmxmp" :
                                    ((dataCenterSpec.getSsl() ? " --ssl" : "") + " -u cassandra -pwf /etc/cassandra/jmxremote.password" )))
                    .addEnvItem(new V1EnvVar().name("ES_SCHEME").value( dataCenterSpec.getSsl() ? "https" : "http"))
                    .addEnvItem(new V1EnvVar().name("HOST_NETWORK").value( Boolean.toString(dataCenterSpec.getHostNetworkEnabled())))
                    .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                    .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    ;

            if (dataCenterSpec.getSsl()) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name("nodetool-ssl-volume")
                        .mountPath("/home/cassandra/.cassandra/nodetool-ssl.properties")
                        .subPath("nodetool-ssl.properties")
                );
            }
            addPortsItem(cassandraContainer, dataCenterSpec.getStoragePort(), "internode", dataCenterSpec.getHostPortEnabled());
            addPortsItem(cassandraContainer, dataCenterSpec.getSslStoragePort(), "internode-ssl", dataCenterSpec.getHostPortEnabled());
            addPortsItem(cassandraContainer, dataCenterSpec.getNativePort(), "cql", dataCenterSpec.getHostPortEnabled());
            addPortsItem(cassandraContainer, dataCenterSpec.getJmxPort(), "jmx", false);
            addPortsItem(cassandraContainer, dataCenterSpec.getJdbPort(), "jdb", false);

            if (dataCenterSpec.getElasticsearchEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name("elasticsearch").containerPort(9200));
                cassandraContainer.addPortsItem(new V1ContainerPort().name("transport").containerPort(9300));
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.ElassandraDaemon"));

                // if enterprise JMX is enabled, stop search with a pre-stop hook
                if (dataCenterSpec.getEnterprise().getJmx()) {
                    cassandraContainer.lifecycle(new V1Lifecycle().preStop(new V1Handler().exec(new V1ExecAction()
                            .addCommandItem("curl")
                            .addCommandItem("-X")
                            .addCommandItem("POST")
                            .addCommandItem("http://localhost:8080/enterprise/search/disable"))));
                }
            } else {
                cassandraContainer.addEnvItem(new V1EnvVar().name("CASSANDRA_DAEMON").value("org.apache.cassandra.service.CassandraDaemon"));
            }

            if (dataCenterSpec.getPrometheusEnabled()) {
                cassandraContainer.addPortsItem(new V1ContainerPort().name("prometheus").containerPort(9500));
            }

            final V1Container sidecarContainer = new V1Container()
                    .name("sidecar")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .env(new ArrayList<>(dataCenterSpec.getEnv()))
                    .addEnvItem(new V1EnvVar()
                            .name("ELASSANDRA_USERNAME")
                            .value("strapkop")
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("ELASSANDRA_PASSWORD")
                            .valueFrom(new V1EnvVarSource()
                                    .secretKeyRef(new V1SecretKeySelector()
                                            .name(OperatorNames.clusterSecret(dataCenter))
                                            .key("cassandra.strapkop_password")))
                    )
                    .image(dataCenterSpec.getSidecarImage())
                    .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                    .securityContext(new V1SecurityContext().runAsUser(999L).runAsGroup(999L))
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
                    .addEnvItem(new V1EnvVar().name("NAMESPACE").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.namespace"))))
                    .addEnvItem(new V1EnvVar().name("POD_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("metadata.name"))))
                    .addEnvItem(new V1EnvVar().name("POD_IP").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("status.podIP"))))
                    .addEnvItem(new V1EnvVar().name("NODE_NAME").valueFrom(new V1EnvVarSource().fieldRef(new V1ObjectFieldSelector().fieldPath("spec.nodeName"))))
                    .addEnvItem(new V1EnvVar().name("JMX_PORT").value(Integer.toString(dataCenterSpec.getJmxPort())));

            String javaToolOptions = "";
            // WARN: Cannot enable SSL on JMXMP because VisualVM does not support it => JMXMP in clear with no auth
            javaToolOptions += dataCenterSpec.getJmxmpEnabled() ?
                    " -Dcassandra.jmxmp " :
                    (dataCenterSpec.getSsl() ? " -Dssl.enable=true " + nodetoolSsl() : "");
            if (javaToolOptions.length() > 0)
                sidecarContainer.addEnvItem(new V1EnvVar().name("JAVA_TOOL_OPTIONS").value(javaToolOptions));

            final V1PodSpec podSpec = new V1PodSpec()
                    .securityContext(new V1PodSecurityContext().fsGroup(999L))
                    .hostNetwork(dataCenterSpec.getHostNetworkEnabled())
                    .addInitContainersItem(buildInitContainerVmMaxMapCount())
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
            String nodeInfoSecretName = System.getenv("NODEINFO_SECRET");
            if (!Strings.isNullOrEmpty(nodeInfoSecretName))
                podSpec.addInitContainersItem(buildInitContainerNodeInfo(nodeInfoSecretName));


            {
                final String secret = dataCenterSpec.getImagePullSecret();
                if (!Strings.isNullOrEmpty(secret)) {
                    final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secret);
                    podSpec.addImagePullSecretsItem(pullSecret);
                }
            }

            // add node affinity for rack, strict or slack (depends on cloud providers PVC cross-zone support)
            final V1NodeSelectorTerm nodeSelectorTerm = new V1NodeSelectorTerm()
                    .addMatchExpressionsItem(new V1NodeSelectorRequirement()
                            .key(OperatorLabels.ZONE).operator("In").addValuesItem(rack));
            switch (dataCenterSpec.getElassandraPodsAffinityPolicy()) {
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

                // provide access to config map volumes in the sidecar, these reside in /tmp though and are not overlayed into /etc/cassandra
                sidecarContainer.addVolumeMountsItem(configMapVolumeMountBuilder.buildV1VolumeMount());

                podSpec.addVolumesItem(new V1Volume()
                        .name(configMapVolumeMountBuilder.mountName)
                        .configMap(configMapVolumeMountBuilder.volumeSource)
                );
            }

            if (dataCenterSpec.getUserSecretVolumeSource() != null) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name("user-secret-volume")
                        .mountPath("/tmp/user-secret-config"));

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
                            .key("cassandra.jmx_password")));
            cassandraContainer.addEnvItem(jmxPasswordEnvVar);
            sidecarContainer.addEnvItem(jmxPasswordEnvVar);

            // mount SSL keystores
            if (dataCenterSpec.getSsl()) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-keystore").mountPath(OPERATOR_KEYSTORE_MOUNT_PATH));
                podSpec.addVolumesItem(new V1Volume().name("operator-keystore")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.keystore(dataCenter))
                                .addItemsItem(new V1KeyToPath().key("keystore.p12").path("keystore.p12"))));

                cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath(authorityManager.getPublicCaMountPath()));
                sidecarContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath(authorityManager.getPublicCaMountPath()));
                podSpec.addVolumesItem(new V1Volume().name("operator-truststore")
                        .secret(new V1SecretVolumeSource()
                                .secretName(authorityManager.getPublicCaSecretName())
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_CACERT_PEM).path(AuthorityManager.SECRET_CACERT_PEM))
                                .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_TRUSTSTORE_P12).path(AuthorityManager.SECRET_TRUSTSTORE_P12))));
            }

            // Cluster secret mounted as config file (e.g AAA shared secret)
            if (dataCenterSpec.getEnterprise() != null && dataCenterSpec.getEnterprise().getAaa() != null && dataCenterSpec.getEnterprise().getAaa().getEnabled()) {
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-cluster-secret").mountPath("/tmp/operator-cluster-secret"));
                podSpec.addVolumesItem(new V1Volume().name("operator-cluster-secret")
                        .secret(new V1SecretVolumeSource().secretName(OperatorNames.clusterSecret(dataCenter))
                                .addItemsItem(new V1KeyToPath().key("shared-secret.yaml").path("elasticsearch.yml.d/003-shared-secret.yaml"))));
                cassandraContainer.addArgsItem("/tmp/operator-cluster-secret");
            }


            if (dataCenterSpec.getRestoreFromBackup() != null) {
                logger.debug("Restore requested.");

                // custom objects api doesn't give us a nice way to pass in the type we want so we do it manually
                final Task backup;
                {
                    final Call call = customObjectsApi.getNamespacedCustomObjectCall("stable.strapdata.com", "v1", "default", "elassandratasks", dataCenterSpec.getRestoreFromBackup(), null, null);
                    backup = customObjectsApi.getApiClient().<Task>execute(call, new TypeToken<Task>() {
                    }.getType()).getData();
                    if (backup.getSpec().getBackup() == null) {
                        throw new StrapkopException(String.format("task %s is not a backup", backup.getMetadata().getName()));
                    }
                }

                podSpec.addInitContainersItem(new V1Container()
                        .name("sidecar-restore")
                        .terminationMessagePolicy("FallbackToLogsOnError")
                        .env(dataCenterSpec.getEnv())
                        .image(dataCenterSpec.getSidecarImage())
                        .imagePullPolicy(dataCenterSpec.getImagePullPolicy())
                        .securityContext(new V1SecurityContext().runAsUser(999L).runAsGroup(999L))
                        .command(ImmutableList.of(
                                "java", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseCGroupMemoryLimitForHeap", "-XX:MaxRAMFraction=2",
                                "-cp", "/app/resources:/app/classes:/app/libs/*",
                                "com.strapdata.strapkop.sidecar.SidecarRestore",
                                "-bb", backup.getSpec().getBackup().getTarget(), // bucket name
                                "-c", dataCenterMetadata.getName(), // clusterID == DcName. Backup dc and restore dc must have the same name
                                "-bi", OperatorNames.stsName(dataCenter, rack), // pod name prefix
                                "-s", backup.getMetadata().getName(), // backup tag used to find the manifest file
                                "--bs", backup.getSpec().getBackup().getType(),
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
                        )
                );
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

            return new V1StatefulSet()
                    .metadata(statefulSetMetadata)
                    .spec(new V1StatefulSetSpec()
                            // if the serviceName references a headless service, kubeDNS to create an A record for
                            // each pod : $(podName).$(serviceName).$(namespace).svc.cluster.local
                            .serviceName(OperatorNames.nodesService(dataCenter))
                            .replicas(replicas)
                            .selector(new V1LabelSelector().matchLabels(rackLabels))
                            .template(new V1PodTemplateSpec()
                                    .metadata(templateMetadata)
                                    .spec(podSpec)
                            )
                            .addVolumeClaimTemplatesItem(new V1PersistentVolumeClaim()
                                    .metadata(new V1ObjectMeta().name("data-volume"))
                                    .spec(dataCenterSpec.getDataVolumeClaim())
                            )
                    );
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
                                    (" && kubectl get pod $(yq .seed_provider[0].parameters[0].seeds /etc/cassandra/cassandra.yaml.d/001-operator-var-overrides.yaml | tr -d '\"' | awk -F \",\" '{for(i=1;i<=NF; i++) { printf(\"%s \", substr($i,1, index($i, \".\")-1))}}') -o jsonpath='{.items[*].status.hostIP}' > /nodeinfo/seeds-ip ") +
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
            return (!sts.isPresent()) ? 0 : sts.get().getStatus().getReplicas();
        }

        public int currentReplicas() { return (!sts.isPresent()) ? 0 : sts.get().getStatus().getCurrentReplicas(); }

        public int readyReplicas() { return (!sts.isPresent()) ? 0 : sts.get().getStatus().getReadyReplicas(); }

        public int freeNodeCount() {
            return size - replicas();
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
                    status.getUpdatedReplicas() < status.getReplicas());
        }

        public boolean isScalingUp() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() < sts.get().getSpec().getReplicas();
        }

        public boolean isScalingDown() {
            V1StatefulSetStatus status = sts.get().getStatus();
            return status.getReplicas() > sts.get().getSpec().getReplicas();
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
        Map<String, Zone> zones = new LinkedHashMap<>();

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
                zones.compute(zoneName, (k,z) -> {
                    if (z == null) {
                        z = new Zone(zoneName);
                    }
                    z.size++;
                    z.setSts(Optional.ofNullable(existingStatefulSetsByZone.get(zoneName)));
                    return z;
                });
            }
        }

        public int totalNodes() {
            return zones.values().stream().map(z -> z.size).reduce(0, Integer::sum);
        }

        public int totalReplicas() {
            return zones.values().stream().map(Zone::replicas).reduce(0, Integer::sum);
        }

        public int totalCurrentReplicas() {
            return zones.values().stream().map(Zone::currentReplicas).reduce(0, Integer::sum);
        }

        public int totalReadyReplicas() {
            return zones.values().stream().map(Zone::readyReplicas).reduce(0, Integer::sum);
        }

        public Optional<Zone> nextToScalueUp() {
            return (totalNodes() == totalReplicas()) ? Optional.empty() : zones.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.freeNodeCount() > 0)
                    // select the preferred zone based on some priorities
                    .min(Zone.scaleComparator);
        }

        public Optional<Zone> nextToScaleDown() {
            return (totalReplicas() == 0) ? Optional.empty() : zones.values().stream()
                    // filter-out full nodes
                    .filter(z -> z.replicas() > 0 || !z.sts.isPresent())
                    // select the preferred zone based on some priorities
                    .max(Zone.scaleComparator);
        }

        public Optional<Zone> first() {
            return zones.values().stream().min(Zone.scaleComparator);
        }

        public boolean isReady() {
            return zones.values().stream().allMatch(Zone::isReady);
        }

        /**
         * Returns an iterator over elements of type {@code T}.
         *
         * @return an Iterator.
         */
        @Override
        public Iterator<Zone> iterator() {
            return zones.values().iterator();
        }
    }

    public Completable updateRack(Zones zones, V1StatefulSet v1StatefulSet, String rack, Map<String, RackStatus> rackStatusMap) throws ApiException {
        logger.debug("DataCenter={} in namespace={} phase={} UPDATING config of rack={}",
                dataCenterMetadata.getName(), rack, dataCenterMetadata.getNamespace(),
                dataCenterStatus.getPhase(), rack);
        updateDatacenterStatus(DataCenterPhase.UPDATING, zones, rackStatusMap);
        return k8sResourceUtils.replaceNamespacedStatefulSet(v1StatefulSet).ignoreElement();
    }


    private void updateDatacenterStatus(DataCenterPhase dcPhase, Zones zones, Map<String, RackStatus> rackStatusMap) {
        final Map<String, ElassandraNodeStatus> podStatuses = new HashMap<>();

        // update pod
        for(Zone zone : zones) {
            for (int i = 0; i < zone.size; i++) {
                ElassandraPod pod = new ElassandraPod(dataCenter, zone.name, i);
                podStatuses.put(pod.getName(), elassandraNodeStatusCache.getOrDefault(pod, ElassandraNodeStatus.UNKNOWN));
            }
        }
        dataCenterStatus.setRackStatuses(Lists.newArrayList(rackStatusMap.values()));
        dataCenterStatus.setElassandraNodeStatuses(podStatuses);

        // update dc status
        dataCenterStatus.setPhase(dcPhase);
        dataCenterStatus.setReplicas(zones.totalReplicas());
        dataCenterStatus.setJoinedReplicas(rackStatusMap.values().stream().collect(Collectors.summingInt(RackStatus::getJoinedReplicas)));
        dataCenterStatus.setReadyReplicas(zones.totalReadyReplicas());
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
