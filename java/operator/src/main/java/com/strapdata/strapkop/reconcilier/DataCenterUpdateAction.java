package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
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
import io.vavr.Tuple2;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Function;
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
    
    private final CqlConnectionManager cqlConnectionManager;
    public final Builder builder = new Builder();

    public DataCenterUpdateAction(final ApplicationContext context,
                                  CoreV1Api coreApi,
                                  AppsV1Api appsApi,
                                  CustomObjectsApi customObjectsApi,
                                  K8sResourceUtils k8sResourceUtils,
                                  AuthorityManager authorityManager,
                                  CqlConnectionManager cqlConnectionManager,
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

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * This class holds information about a kubernetes zone (which is an elassandra rack)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class Zone {
        String name;
        Integer size;
        V1StatefulSet sts;
        Integer replicas;
        Integer remainingSlots;
        
        // this scaleComparator returns zone where to add next replicas first
        private static final Comparator<Zone> scaleComparator = Comparator
                // smallest replicas first
                .comparingInt(Zone::getReplicas)
                // in case there is equality, pick the zone with the most slots available
                .thenComparingInt(z -> -z.getRemainingSlots())
                // finally use lexical order of the zone name
                .thenComparing(Zone::getName);
    }

    /**
     * Given the existing statefulsets sorted by zone, construct a list of zones
     *
     * @param existingStatefulSetsByZone the map of zone name -> existing statefulset
     * @return a list of zone object
     * @throws ApiException if the k8s node api call failed
     */
    private List<Zone> createZoneList(Map<String, V1StatefulSet> existingStatefulSetsByZone) throws ApiException {
        
        return countNodesPerZone().entrySet()
                .stream()
                .map(e -> new Zone()
                        .setName(e.getKey())
                        .setSize(e.getValue())
                        .setSts(existingStatefulSetsByZone.get(e.getKey())))
                .map(zone -> zone.setReplicas(zone.getSts() == null ? 0 : zone.getSts().getSpec().getReplicas()))
                .map(zone -> zone.setRemainingSlots(zone.getSize() - zone.getReplicas()))
                .collect(Collectors.toList());
    }
    
    
    /**
     * Get the number of k8s nodes per zone. We call the k8s api and don't reuse the cache because at startup a reconciliation
     * can be started before the cache node is fully populated),
     *
     * @return the map containing the counting for each zone name
     * @throws ApiException if the k8s node api call failed
     */
    private Map<String, Integer> countNodesPerZone() throws ApiException {
        return coreApi.listNode(false, null, null, null, null, null, null, null, null)
                .getItems().stream()
                .map(node -> {
                    String zoneName = node.getMetadata().getLabels().get(OperatorLabels.ZONE);
                    if (zoneName == null) {
                        throw new RuntimeException(new StrapkopException(String.format("missing label %s on node %s", OperatorLabels.ZONE, node.getMetadata().getName())));
                    }
                    return zoneName;
                })
                .collect(Collectors.groupingBy(Function.identity(), Collectors.reducing(0, e -> 1, Integer::sum)));
    }
    
    /**
     * Fetch existing statefulsets from k8s api and sort then by zone name
     *
     * @return a map of zone name -> statefulset
     * @throws ApiException      if there is an error with k8s api
     * @throws StrapkopException if the statefulset has no RACK label or if two statefulsets has the same zone label
     */
    private TreeMap<String, V1StatefulSet> fetchExistingStatefulSetsByZone() throws ApiException, StrapkopException {
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
    
    
    /**
     * Count the total number of replicas according to statefulsets (both ready and unready)
     *
     * @param zones the list of zones
     * @return the total number of replicas
     */
    private Integer countTotalObservedReplicas(List<Zone> zones) {
        return zones.stream().map(Zone::getReplicas).reduce(0, Integer::sum);
    }
    
    /**
     * Find the zone that we have to deploy the next replicas into
     *
     * @param zones the list of zones
     * @return the zone that has been elected
     * @throws StrapkopException if we can't find a zone to add a replicas
     */
    private Zone findNextZoneToAddReplicas(List<Zone> zones) throws StrapkopException {
        return zones.stream()
                // filter-out full nodes
                .filter(z -> z.getRemainingSlots() > 0)
                
                // select the preferred zone based on some priorities
                .min(Zone.scaleComparator)
                
                // throw an exception in case node zone is available
                .orElseThrow(
                        () -> new StrapkopException(String.format("No more node available to scale out dc=%s", dataCenter.getMetadata().getName()))
                );
    }
    
    
    private Zone findNextZoneToRemoveReplicas(List<Zone> zones) throws StrapkopException {
        return zones.stream()
                // filter-out empty nodes
                .filter(z -> z.replicas > 0)
                
                // select the preferred zone based on some priorities
                .max(Zone.scaleComparator)
                
                // throw an exception in case node zone is available
                .orElseThrow(
                        () -> new StrapkopException(String.format("No more node available to scale in dc=%s", dataCenter.getMetadata().getName()))
                );
    }



    public Completable reconcileDataCenter() throws Exception {
        logger.info("Reconciling DataCenter {} in namespace={}, phase={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace(), dataCenterStatus.getPhase());

        if (dataCenterSpec.getReplicas() <= 0) {
            throw new StrapkopException(String.format("dc=%s has an invalid number of replicas", dataCenterMetadata.getName()));
        }
        
        // get the existing sts sorted by zone/rack name
        final TreeMap<String, V1StatefulSet> existingStsMap = fetchExistingStatefulSetsByZone();

        // Deploy Kuberenetes Services
        k8sResourceUtils.createOrReplaceNamespacedService(builder.buildNodesService()).subscribe();
        k8sResourceUtils.createOrReplaceNamespacedService(builder.buildElasticsearchService()).subscribe();
        k8sResourceUtils.createOrReplaceNamespacedService(builder.buildExternalNodesService()).subscribe();
        for(String rack : existingStsMap.keySet()) // create a seed service per DC
            k8sResourceUtils.createOrReplaceNamespacedService(builder.buildSeedService(rack)).subscribe();

        // Deploy Secrets if needed
        V1ObjectMeta clusterSecretObjectMeta = builder.clusterObjectMeta(OperatorNames.clusterSecret(dataCenter));
        V1Secret clusterSecret = getOrCreateSecret(clusterSecretObjectMeta, () ->
            new V1Secret()
                    .metadata(clusterSecretObjectMeta)
                    // replace the default cassandra password
                    .putStringDataItem("cassandra.cassandra_password", UUID.randomUUID().toString())
                    // strapkop role is used by the operator and sidecar
                    .putStringDataItem("cassandra.strapkop_password", UUID.randomUUID().toString())
                    // admin is intended to be distributed to human administrator, so the credentials lifecycle is decoupled
                    .putStringDataItem("cassandra.admin_password", UUID.randomUUID().toString())
                    // password used by reaper to access its cassandra backend
                    .putStringDataItem("cassandra.reaper_password", UUID.randomUUID().toString())

                    // password used to access reaper webui and api
                    .putStringDataItem("reaper.admin_password", UUID.randomUUID().toString())

                    // cassandra JMX password, mounted as /etc/cassandra/jmxremote.password
                    .putStringDataItem("cassandra.jmx_password", UUID.randomUUID().toString())
                    // elassandra-enterprise shared secret is intended to be mounted as a config fragment
                    .putStringDataItem("shared-secret.yaml", "aaa.shared_secret: " + UUID.randomUUID().toString())
        );
        // update elassandra keystores
        if (dataCenterSpec.getSsl())
            getOrCreateSecret(builder.dataCenterObjectMeta(OperatorNames.keystore(dataCenter)), () -> builder.buildKeystoreSecret());
        // update curlrc + cqlshrc  file
        getOrCreateSecret(builder.clusterObjectMeta(OperatorNames.clusterRcFilesSecret(dataCenter)),
                () -> builder.buildRcFileSecret("admin", clusterSecret.getStringData().get("cassandra.admin_password")));

        // deploy configMaps
        Tuple2<io.vavr.collection.List<ConfigMapVolumeMount>, String> tuple = builder.buildConfigMapVolumeMountBuilders(dataCenterSpec);
        io.vavr.collection.List<ConfigMapVolumeMount> configMapVolumeMountList = tuple._1;
        String configFingerprint = tuple._2;
        for(ConfigMapVolumeMount configMapVolumeMount : configMapVolumeMountList)
            configMapVolumeMount.createOrReplaceNamespacedConfigMap();


        dataCenterStatus.setConfigMapFingerPrint(configFingerprint);

        for(V1StatefulSet v1StatefulSet : existingStsMap.values()) {
            String stsFingerprint = v1StatefulSet.getSpec().getTemplate().getMetadata().getAnnotations().get("configmap-fingerprint");
            if (stsFingerprint != configFingerprint) {
                // update the config spec fingerprint => Update statefulset having a different fingerprint if PHASE <> SCALE_...
                String rack = v1StatefulSet.getSpec().getTemplate().getMetadata().getLabels().get("rack");
                int replicas = v1StatefulSet.getSpec().getReplicas();
                logger.debug("Need to update config fingerprint={} for statefulset={}, rack={}, replicas={}, phase={}",
                        configFingerprint, v1StatefulSet.getMetadata().getName(), rack, replicas, dataCenterStatus.getPhase());
                switch(dataCenterStatus.getPhase()) {
                    case RUNNING:
                    case ERROR:
                    case UPDATING:
                        context.createBean(StatefulSetUpdateAction.class).updateRack(dataCenter, builder.buildRackStatefulSet(rack, replicas));
                        updateStatus();
                        logger.debug("Datacenter CONFIG reconciled DataCenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
                        return Completable.complete();
                }
            }
        }

        if (DataCenterPhase.RUNNING.equals(dataCenterStatus.getPhase()) || DataCenterPhase.CREATING.equals(dataCenterStatus.getPhase())) {
            updateDatacenterScale(existingStsMap, configMapVolumeMountList, configFingerprint);
            logger.debug("Datacenter SCALE reconciled for DataCenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
        } else {
            logger.debug("Datacenter reconciled for DataCenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
        }

        updateStatus();
        return Completable.complete();
    }

    /**
     * Update Datacenter size.
     */
    public void updateDatacenterScale(final TreeMap<String, V1StatefulSet> existingStsMap,
                                     io.vavr.collection.List<ConfigMapVolumeMount> configMapVolumeMountList,
                                     String configFingerprint) throws Exception {
        // Lookup zones to adjust DC size
        // create a list of zone object using the k8s node api and the existing statefulsets
        final List<Zone> zones = createZoneList(existingStsMap);
        // TODO: check zones not empty ?

        // check if we need to add or remove elassandra replicas
        final int totalObservedReplicas = countTotalObservedReplicas(zones);
        if (totalObservedReplicas < dataCenterSpec.getReplicas()) {
            // add node
            final Zone zone = findNextZoneToAddReplicas(zones);
            zone.setReplicas(zone.getReplicas() + 1);
        } else if (totalObservedReplicas > dataCenterSpec.getReplicas()) {
            // remove node
            final Zone zone = findNextZoneToRemoveReplicas(zones);
            zone.setReplicas(zone.getReplicas() - 1);
        }

        // Deploy Statefulset
        // sorted map of zone -> statefulset (sorted by zone name)
        TreeMap<String, V1StatefulSet> newStatefulSetMap = new TreeMap<>();
        // loop over zones to construct the new statefulset map
        for (Zone zone : zones) {
            final V1StatefulSet sts = builder.buildRackStatefulSet(
                    zone.getName(), zone.getReplicas(),
                    configMapVolumeMountList.prepend(builder.buildRackConfigMap(zone.getName()).createOrReplaceNamespacedConfigMap()),
                    configFingerprint);
            newStatefulSetMap.put(zone.getName(), sts);
        }
        createOrScaleAllStatefulsets(existingStsMap, newStatefulSetMap);
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
            final List<Zone> seedZoneCandidates = zones.stream().filter(z -> z.getReplicas() > 0).collect(Collectors.toList());
            if (seedZoneCandidates.size() != 1) {
                throw new StrapkopException(String.format("internal error, can't determine the seed node dc=%s", dataCenterMetadata.getName()));
            }
            seedRack = seedZoneCandidates.get(0).getName();
        }
        return seedRack;
    }
    
    // this only modify the status object, but does not actually commit it to k8s api.
    // the root DataCenterReconcilier is in charge of calling the update api
    private void updateStatus() {

    }

    public class ConfigMapVolumeMount {
        public final V1ConfigMap configMap;
        public final V1ConfigMapVolumeSource volumeSource;
        public final String mountName, mountPath;

        public ConfigMapVolumeMount(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this.configMap = configMap;
            this.volumeSource = volumeSource;
            this.mountName = mountName;
            this.mountPath = mountPath;
        }

        public ConfigMapVolumeMount(final V1ConfigMapVolumeSource volumeSource, final String mountName, final String mountPath) {
            this(null, volumeSource, mountName, mountPath);
        }

        public ConfigMapVolumeMount createOrReplaceNamespacedConfigMap() throws ApiException {
            if (configMap != null)
                k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
            return this;
        }

        public String fingerPrint() {
            return DigestUtils.sha1Hex(appsApi.getApiClient().getJSON().getGson().toJson(configMap.getData()));
        }

        public ConfigMapVolumeMount addFile(final String path, final String content) {
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

    public class Builder {

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

        private V1ObjectMeta clusterObjectMeta(final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(OperatorLabels.cluster(dataCenterSpec.getClusterName()));
        }

        private V1ObjectMeta dataCenterObjectMeta(final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(dataCenterLabels)
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
        }

        private V1ObjectMeta rackObjectMeta(final String rack, final String name) {
            return new V1ObjectMeta()
                    .name(name)
                    .namespace(dataCenterMetadata.getNamespace())
                    .labels(OperatorLabels.rack(dataCenter, rack))
                    .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
        }

        public V1Service buildExternalNodesService() throws ApiException {
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
        private V1Service buildSeedService(String rack) throws ApiException {
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

        public V1Service buildNodesService() {
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

        public V1Service buildElasticsearchService() {
            return new V1Service()
                    .metadata(dataCenterObjectMeta(OperatorNames.elasticsearchService(dataCenter)))
                    .spec(new V1ServiceSpec()
                            .type("ClusterIP")
                            .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                            .selector(dataCenterLabels)
                    );
        }

        // configuration that is supposed to be variable over the cluster life and does not require a rolling restart when changed
        public ConfigMapVolumeMount buildVarConfigMap() {
            final V1ConfigMap configMap = new V1ConfigMap()
                    .metadata(dataCenterObjectMeta(OperatorNames.varConfig(dataCenter)));

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
            seeds.addAll(remoteSeeds);
            Map<String, Boolean> rackSeedBootstraped = dataCenter.getStatus().getRackStatuses().stream().collect(Collectors.toMap(s -> s.getName(), s -> s.getSeedBootstrapped()));
            int firstSeedableRack = 0;
            if (remoteSeeds.isEmpty() && remoteSeeders.isEmpty()) {
                // if we are the first rack, no question, first node is a seed.
                firstSeedableRack = 1;
                seeds.add(OperatorNames.podName(dataCenter, "0", 0) + "." + OperatorNames.stsName(dataCenter, Integer.toString(0)) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local");
            }
            // add bootstrapped rack
            for (int i = firstSeedableRack; i < rackSeedBootstraped.size(); i++) {
                if (rackSeedBootstraped.getOrDefault(Integer.toString(i), false))
                    seeds.add(OperatorNames.podName(dataCenter, Integer.toString(i), 0) + "." + OperatorNames.stsName(dataCenter, Integer.toString(i)) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local");
            }
            logger.debug("remoteSeeds={} remoteSeeders={}, rackSeedBootstraped={} seeds={}", remoteSeeds, remoteSeeders, rackSeedBootstraped, seeds);

            Map<String, String> parameters = new HashMap<>();
            if (!seeds.isEmpty())
                parameters.put("seeds", String.join(", ", seeds));
            if (!remoteSeeders.isEmpty())
                parameters.put("seeders", String.join(", ", remoteSeeders));
            logger.debug("seed parameters={}", parameters);
            final Map<String, Object> config = new HashMap<>(); // can't use ImmutableMap as some values are null
            config.put("seed_provider", ImmutableList.of(ImmutableMap.of(
                    "class_name", "com.strapdata.cassandra.k8s.SeedProvider",
                    "parameters", ImmutableList.of(parameters))
            ));
            return new ConfigMapVolumeMount(configMap, volumeSource, "operator-var-config-volume", "/tmp/operator-var-config")
                    .addFile("cassandra.yaml.d/001-operator-var-overrides.yaml", toYamlString(config));
        }

        public ConfigMapVolumeMount buildSpecConfigMap() {
            final V1ConfigMap configMap = new V1ConfigMap().metadata(dataCenterObjectMeta(OperatorNames.specConfig(dataCenter)));
            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
            final ConfigMapVolumeMount configMapVolumeMountBuilder =
                    new ConfigMapVolumeMount(configMap, volumeSource, "operator-spec-config-volume", "/tmp/operator-spec-config");

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

                configMapVolumeMountBuilder.addFile("cassandra.yaml.d/001-operator-spec-overrides.yaml", toYamlString(config));
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

            return configMapVolumeMountBuilder;
        }

        // configuration that is specific to rack. For the moment, an update of it does not trigger a restart
        private ConfigMapVolumeMount buildRackConfigMap(final String rack) throws IOException, ApiException {
            final V1ConfigMap configMap = new V1ConfigMap()
                    .metadata(rackObjectMeta(rack, OperatorNames.rackConfig(dataCenter, rack)));

            final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());

            // GossipingPropertyFileSnitch config
            final Properties rackDcProperties = new Properties();

            rackDcProperties.setProperty("dc", dataCenterSpec.getDatacenterName());
            rackDcProperties.setProperty("rack", rack);
            rackDcProperties.setProperty("prefer_local", "true");

            final StringWriter writer = new StringWriter();
            rackDcProperties.store(writer, "generated by cassandra-operator");

            return new ConfigMapVolumeMount(configMap, volumeSource, "operator-rack-config-volume", "/tmp/operator-rack-config")
                    .addFile("cassandra-rackdc.properties", writer.toString());
        }

        /**
         * Build global config and config fingerprint
         * @return
         */
        public Tuple2<io.vavr.collection.List<ConfigMapVolumeMount>, String> buildConfigMapVolumeMountBuilders(DataCenterSpec dataCenterSpec) {
            // create configmaps and their volume mounts (operator-defined config, and user overrides)
            // vavr.List is used here because it allows multiple head singly linked list (heads contain the rack-specific config map)
            io.vavr.collection.List<ConfigMapVolumeMount> configMapVolumeMounts = io.vavr.collection.List.empty();
            ConfigMapVolumeMount specConfigMapMountBuilder = buildSpecConfigMap();
            configMapVolumeMounts = configMapVolumeMounts.prepend(specConfigMapMountBuilder);
            // the hash of the spec config map used to trigger automatic rolling restart when config map changed
            configMapVolumeMounts = configMapVolumeMounts.prepend(buildVarConfigMap());
            if (dataCenterSpec.getUserConfigMapVolumeSource() != null) {
                logger.trace("Adding UserConfigMapVolumeSource={}", dataCenterSpec.getUserConfigMapVolumeSource().getName());
                configMapVolumeMounts = configMapVolumeMounts.prepend
                        (new ConfigMapVolumeMount(null, dataCenterSpec.getUserConfigMapVolumeSource(), "user-config-volume", "/tmp/user-config"));
            }

            return new Tuple2<>(configMapVolumeMounts, specConfigMapMountBuilder.fingerPrint());
        }


        public V1Secret buildKeystoreSecret() throws Exception {
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
        private V1Secret buildRcFileSecret(String username, String password) throws ApiException {
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

        public V1StatefulSet buildRackStatefulSet(String zoneName, int replicas) throws IOException, ApiException, StrapkopException {
            Zone zone = new Zone().setName(zoneName).setReplicas(replicas);
            Tuple2<io.vavr.collection.List<ConfigMapVolumeMount>, String> tuple = builder.buildConfigMapVolumeMountBuilders(dataCenterSpec);
            io.vavr.collection.List<ConfigMapVolumeMount> configMapVolumeMountList = tuple._1;
            String configFingerprint = tuple._2;
            return builder.buildRackStatefulSet(
                    zone.getName(), zone.getReplicas(),
                    configMapVolumeMountList.prepend(builder.buildRackConfigMap(zone.getName())), configFingerprint);
        }

        public V1StatefulSet buildRackStatefulSet(final String rack, final int replicas, final Iterable<ConfigMapVolumeMount> configMapVolumeMounts, String configmapFingerprint) throws ApiException, StrapkopException {
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
                    .addInitContainersItem(buildVmMaxMapCountInitContainer())
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
                podSpec.addInitContainersItem(buildNodeInfoInitContainer(nodeInfoSecretName));


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
            for (final ConfigMapVolumeMount configMapVolumeMount : configMapVolumeMounts) {
                logger.debug("Adding configMapVolumeMount name={} path={}", configMapVolumeMount.mountName, configMapVolumeMount.mountPath);
                cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name(configMapVolumeMount.mountName)
                        .mountPath(configMapVolumeMount.mountPath)
                );

                // provide access to config map volumes in the sidecar, these reside in /tmp though and are not overlayed into /etc/cassandra
                sidecarContainer.addVolumeMountsItem(new V1VolumeMount()
                        .name(configMapVolumeMount.mountName)
                        .mountPath(configMapVolumeMount.mountPath));

                // the Cassandra container entrypoint overlays configmap volumes
                cassandraContainer.addArgsItem(configMapVolumeMount.mountPath);

                podSpec.addVolumesItem(new V1Volume()
                        .name(configMapVolumeMount.mountName)
                        .configMap(configMapVolumeMount.volumeSource)
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
                    .putAnnotationsItem(OperatorLabels.CONFIGMAP_FINGERPRINT, configmapFingerprint);

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

        private V1Container buildVmMaxMapCountInitContainer() {
            return new V1Container()
                    .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                    .name("increase-vm-max-map-count")
                    .image("busybox")
                    .imagePullPolicy("IfNotPresent")
                    .terminationMessagePolicy("FallbackToLogsOnError")
                    .command(ImmutableList.of("sysctl", "-w", "vm.max_map_count=1048575"));
        }

        // Nodeinfo init container if NODEINFO_SECRET is available as env var
        private V1Container buildNodeInfoInitContainer(String nodeInfoSecretName) {
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

    private void createOrScaleAllStatefulsets(final TreeMap<String, V1StatefulSet> existingStatefulSetMap,
                                              final TreeMap<String, V1StatefulSet> newStatefulSetMap) throws Exception {
        // first we ensure all statefulset are created, if not we create the ones that are missing with 0 replicas then abort the reconciliation
        for (Map.Entry<String, V1StatefulSet> entry : newStatefulSetMap.entrySet()) {
            final String rack = entry.getKey();
            final V1StatefulSet sts = entry.getValue();
            
            // try to read the existing statefulset
            if (!existingStatefulSetMap.containsKey(rack)) {
                sts.getSpec().setReplicas(0);
                appsApi.createNamespacedStatefulSet(sts.getMetadata().getNamespace(), sts, null, null, null);
            }
        }
        
        // if some of the sts didn't exist we abort the reconciliation for now. The sts creation will trigger a new reconciliation anyway
        if (existingStatefulSetMap.size() < newStatefulSetMap.size()) {
            dataCenter.getStatus().setPhase(DataCenterPhase.CREATING);
            return;
        }
        
        // then we delegate the complex stuff to another class...
        // this will only update one statefulset at once, taking care of everything
        context.createBean(StatefulSetUpdateAction.class).updateNextStatefulSet(dataCenter, existingStatefulSetMap, newStatefulSetMap);
    }

    private V1Secret getOrCreateSecret(V1ObjectMeta secretMetadata, ThrowingSupplier<V1Secret> secretSupplier) throws Exception {
        // check if secret exists
        try {
            return coreApi.readNamespacedSecret(secretMetadata.getName(), secretMetadata.getNamespace(), null, null, null);
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        V1Secret secret = secretSupplier.get();
        coreApi.createNamespacedSecret(dataCenterMetadata.getNamespace(), secret, null, null, null);
        return secret;
    }


    private static String toYamlString(final Object object) {
        final DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(object);
    }
}
