package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.*;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.vavr.Tuple;
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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Prototype
public class DataCenterUpdateAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterUpdateAction.class);
    
    private final CoreV1Api coreApi;
    private final AppsV1Api appsApi;
    private final CustomObjectsApi customObjectsApi;
    private final K8sResourceUtils k8sResourceUtils;
    private final AuthorityManager authorityManager;
    
    private final DataCenter dataCenter;
    private final V1ObjectMeta dataCenterMetadata;
    private final DataCenterSpec dataCenterSpec;
    private final DataCenterStatus dataCenterStatus;
    private final Map<String, String> dataCenterLabels;
    
    private final StatefulSetUpdateAction statefulSetUpdateAction;
    private final CqlConnectionManager cqlConnectionManager;

    public static final String OPERATOR_KEYSTORE_MOUNT_PATH = "/tmp/operator-keystore";
    public static final String OPERATOR_KEYSTORE = "keystore.p12";
    public static final String OPERATOR_KEYPASS = "changeit";

    public DataCenterUpdateAction(CoreV1Api coreApi,
                                  AppsV1Api appsApi,
                                  CustomObjectsApi customObjectsApi,
                                  K8sResourceUtils k8sResourceUtils,
                                  AuthorityManager authorityManager,
                                  StatefulSetUpdateAction statefulSetUpdateAction,
                                  CqlConnectionManager cqlConnectionManager,
                                  @Parameter("dataCenter") DataCenter dataCenter) {
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        
        this.dataCenter = dataCenter;
        this.dataCenterMetadata = dataCenter.getMetadata();
        this.dataCenterSpec = dataCenter.getSpec();
        this.statefulSetUpdateAction = statefulSetUpdateAction;
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
    
    private void validateSpec() throws StrapkopException {
        if (dataCenterSpec.getReplicas() <= 0) {
            throw new StrapkopException(String.format("dc=%s has an invalid number of replicas", dataCenterMetadata.getName()));
        }
    }
    
    void reconcileDataCenter() throws Exception {
        logger.info("Reconciling DataCenter {} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
        
        validateSpec();
        
        // get the existing sts sorted by zone/rack name
        final TreeMap<String, V1StatefulSet> existingStsMap = fetchExistingStatefulSetsByZone();
        
        // create a list of zone object using the k8s node api and the existing statefulsets
        final List<Zone> zones = createZoneList(existingStsMap);
        
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
        
        // create the public service (what clients use to discover the data center)
        createOrReplaceNodesService();
        
        // create the public service to access elasticsearch
        createOrReplaceElasticsearchService();

        // create a seed service per DC
        for(String rack : existingStsMap.keySet())
            createOrReplaceSeedService(rack);

        // create a NodePort service for all nodes
        createOrReplaceExternalNodesService();

        if (dataCenterSpec.getSsl()) {
            createKeystoreIfNotExists();
        }
        
        Tuple2<String, String> adminCredential = createClusterSecretIfNotExists();
        createOrUpdateRcFileSecret(adminCredential._1, adminCredential._2);

        // create configmaps and their volume mounts (operator-defined config, and user overrides)
        // vavr.List is used here because it allows multiple head singly linked list (heads contain the rack-specific config map)
        io.vavr.collection.List<ConfigMapVolumeMount> configMapVolumeMounts = io.vavr.collection.List.empty();
        Tuple2<ConfigMapVolumeMount, String> tuple = createOrReplaceSpecConfigMap();
        configMapVolumeMounts = configMapVolumeMounts.prepend(tuple._1);
        // the hash of the spec config map used to trigger automatic rolling restart when config map changed
        final String configFingerprint = tuple._2;
        configMapVolumeMounts = configMapVolumeMounts.prepend(createOrReplaceVarConfigMap());
        if (dataCenterSpec.getUserConfigMapVolumeSource() != null) {
            logger.trace("Adding UserConfigMapVolumeSource={}", dataCenterSpec.getUserConfigMapVolumeSource().getName());
            configMapVolumeMounts = configMapVolumeMounts.prepend
                    (new ConfigMapVolumeMount("user-config-volume", "/tmp/user-config", dataCenterSpec.getUserConfigMapVolumeSource()));
        }

        // sorted map of zone -> statefulset (sorted by zone name)
        TreeMap<String, V1StatefulSet> newStatefulSetMap = new TreeMap<>();
        // TODO: ensure validation of zones > 0
        
        // loop over zones to construct the new statefulset map
        for (Zone zone : zones) {
            final V1StatefulSet sts = constructZoneStatefulSet(
                    zone.getName(), zone.getReplicas(),
                    configMapVolumeMounts.prepend(createOrReplaceRackConfigMap(zone.getName())), configFingerprint);
            newStatefulSetMap.put(zone.getName(), sts);
        }
        
        createOrScaleAllStatefulsets(newStatefulSetMap, existingStsMap);

        updateStatus();
        
        logger.debug("Reconciled DataCenter={} in namespace={}", dataCenterMetadata.getName(), dataCenterMetadata.getNamespace());
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
    
    private V1ObjectMeta clusterChildObjectMetadata(final String name) {
        return new V1ObjectMeta()
                .name(name)
                .namespace(dataCenterMetadata.getNamespace())
                .labels(OperatorLabels.cluster(dataCenterSpec.getClusterName()));
    }
    
    private V1ObjectMeta dataCenterChildObjectMetadata(final String name) {
        return new V1ObjectMeta()
                .name(name)
                .namespace(dataCenterMetadata.getNamespace())
                .labels(dataCenterLabels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
    }
    
    private V1ObjectMeta rackChildObjectMetadata(final String rack, final String name) {
        return new V1ObjectMeta()
                .name(name)
                .namespace(dataCenterMetadata.getNamespace())
                .labels(OperatorLabels.rack(dataCenter, rack))
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
    }

    private void addPortsItem(V1Container container, int port, String name, boolean withHostPort) {
        if (port > 0) {
            V1ContainerPort v1Port = new V1ContainerPort().name(name).containerPort(port);
            container.addPortsItem((dataCenterSpec.getHostPortEnabled() && withHostPort) ? v1Port.hostPort(port) : v1Port);
        }
    }
    
    private V1StatefulSet constructZoneStatefulSet(final String rack, final int replicas, final Iterable<ConfigMapVolumeMount> configMapVolumeMounts, String configmapFingerprint) throws ApiException, StrapkopException {
        final V1ObjectMeta statefulSetMetadata = rackChildObjectMetadata(rack, OperatorNames.stsName(dataCenter, rack));
        
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
                .addEnvItem(new V1EnvVar().name("CASSANDRA_CGROUP_MEMORY_LIMIT").value("true"))
                .addEnvItem(new V1EnvVar().name("CQLS_OPTS").value( dataCenterSpec.getSsl() ? "--ssl" : ""))
                .addEnvItem(new V1EnvVar().name("NODETOOL_OPTS").value( (dataCenterSpec.getSsl() ? "--ssl" : "") +  " -u cassandra -pwf /etc/cassandra/jmxremote.password"))
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

        if (dataCenterSpec.getSsl()) {
            sidecarContainer.addEnvItem(new V1EnvVar().name("JAVA_TOOL_OPTIONS").value("-Dssl.enable=true "+ nodetoolSsl()));
        }
        
        final V1PodSpec podSpec = new V1PodSpec()
                .securityContext(new V1PodSecurityContext().fsGroup(999L))
                .hostNetwork(dataCenterSpec.getHostNetworkEnabled())
                .addInitContainersItem(vmMaxMapCountInit())
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
            podSpec.addInitContainersItem(nodeInfoInit(nodeInfoSecretName));


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
            logger.trace("Adding configMapVolumeMount name={} path={}", configMapVolumeMount.name, configMapVolumeMount.mountPath);
            cassandraContainer.addVolumeMountsItem(new V1VolumeMount()
                    .name(configMapVolumeMount.name)
                    .mountPath(configMapVolumeMount.mountPath)
            );
            
            // provide access to config map volumes in the sidecar, these reside in /tmp though and are not overlayed into /etc/cassandra
            sidecarContainer.addVolumeMountsItem(new V1VolumeMount()
                    .name(configMapVolumeMount.name)
                    .mountPath(configMapVolumeMount.mountPath));
            
            // the Cassandra container entrypoint overlays configmap volumes
            cassandraContainer.addArgsItem(configMapVolumeMount.mountPath);

            podSpec.addVolumesItem(new V1Volume()
                    .name(configMapVolumeMount.name)
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

            cassandraContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath(this.authorityManager.getPublicCaMountPath()));
            sidecarContainer.addVolumeMountsItem(new V1VolumeMount().name("operator-truststore").mountPath(this.authorityManager.getPublicCaMountPath()));
            podSpec.addVolumesItem(new V1Volume().name("operator-truststore")
                    .secret(new V1SecretVolumeSource()
                            .secretName(this.authorityManager.getPublicCaSecretName())
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
    
    private V1Container vmMaxMapCountInit() {
        return new V1Container()
                .securityContext(new V1SecurityContext().privileged(dataCenterSpec.getPrivilegedSupported()))
                .name("increase-vm-max-map-count")
                .image("busybox")
                .imagePullPolicy("IfNotPresent")
                .terminationMessagePolicy("FallbackToLogsOnError")
                .command(ImmutableList.of("sysctl", "-w", "vm.max_map_count=1048575"));
    }
    
    // Nodeinfo init container if NODEINFO_SECRET is available as env var
    private V1Container nodeInfoInit(String nodeInfoSecretName) {
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

    private static void configMapVolumeAddFile(final V1ConfigMap configMap, final V1ConfigMapVolumeSource volumeSource, final String path, final String content) {
        final String encodedKey = path.replaceAll("\\W", "_");

        configMap.putDataItem(encodedKey, content);
        volumeSource.addItemsItem(new V1KeyToPath().key(encodedKey).path(path));
    }

    private static String toYamlString(final Object object) {
        final DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(object);
    }

    public class ConfigMapVolumeMount {
        public final String name, mountPath;

        public final V1ConfigMapVolumeSource volumeSource;

        public ConfigMapVolumeMount(final String name, final String mountPath, final V1ConfigMapVolumeSource volumeSource) {
            this.name = name;
            this.mountPath = mountPath;
            this.volumeSource = volumeSource;
        }
    }
    
    private static final long MB = 1024 * 1024;
    private static final long GB = MB * 1024;
    
    // configuration that is supposed to be variable over the cluster life and does not require a rolling restart when changed
    private ConfigMapVolumeMount createOrReplaceVarConfigMap() throws ApiException {
        final V1ConfigMap configMap = new V1ConfigMap()
                .metadata(dataCenterChildObjectMetadata(OperatorNames.varConfig(dataCenter)));
        
        final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
        
        // cassandra.yaml overrides
        {
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
                seeds.add(OperatorNames.podName(dataCenter, "0", 0) + "." + OperatorNames.stsName(dataCenter, Integer.toString(0)) + "." + dataCenterMetadata.getNamespace()+".svc.cluster.local");
            }
            // add bootstrapped rack
            for(int i = firstSeedableRack; i < rackSeedBootstraped.size(); i++) {
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
            configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/001-operator-var-overrides.yaml", toYamlString(config));
        }
        
        // GossipingPropertyFileSnitch config
        k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
        return new ConfigMapVolumeMount("operator-var-config-volume", "/tmp/operator-var-config", volumeSource);
    }
    
    // configuration that is specific to rack. For the moment, an update of it does not trigger a restart
    private ConfigMapVolumeMount createOrReplaceRackConfigMap(final String rack) throws IOException, ApiException {
        final V1ConfigMap configMap = new V1ConfigMap()
                .metadata(rackChildObjectMetadata(rack, OperatorNames.rackConfig(dataCenter, rack)));
        
        final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
        
        // GossipingPropertyFileSnitch config
        {
            final Properties rackDcProperties = new Properties();
            
            rackDcProperties.setProperty("dc", dataCenterSpec.getDatacenterName());
            rackDcProperties.setProperty("rack", rack);
            rackDcProperties.setProperty("prefer_local", "true");
            
            final StringWriter writer = new StringWriter();
            rackDcProperties.store(writer, "generated by cassandra-operator");
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-rackdc.properties", writer.toString());
        }
        
        k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
        return new ConfigMapVolumeMount("operator-rack-config-volume", "/tmp/operator-rack-config", volumeSource);
    }
    
    // configuration that should trigger a rolling restart when modified
    private Tuple2<ConfigMapVolumeMount, String> createOrReplaceSpecConfigMap() throws ApiException {
        final V1ConfigMap configMap = new V1ConfigMap()
                .metadata(dataCenterChildObjectMetadata(OperatorNames.specConfig(dataCenter)));
        
        final V1ConfigMapVolumeSource volumeSource = new V1ConfigMapVolumeSource().name(configMap.getMetadata().getName());
        
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

            configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/001-operator-spec-overrides.yaml", toYamlString(config));
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
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/001-cassandra-exporter.sh",
                    "JVM_OPTS=\"${JVM_OPTS} -javaagent:${CASSANDRA_HOME}/agents/jmx_prometheus_javaagent-0.3.1.jar=9500:${CASSANDRA_CONF}/jmx_prometheus_exporter.yml\"");
        }

        // Add JMX configuration
        // Remote JMX require SSL, otherwise this is local clear JMX
        if (dataCenterSpec.getSsl()) {
            configMapVolumeAddFile(configMap, volumeSource, "jvm.options.d/001-jmx-ssl.options",
                    "-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort() + "\n" +
                            "-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n" +
                            "-Dcom.sun.management.jmxremote.authenticate=true\n" +
                            "-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n" +
                            //"-Dcom.sun.management.jmxremote.access.file=/etc/cassandra/jmxremote.access\n" + \
                            "-Dcom.sun.management.jmxremote.ssl=true\n" +
                            "-Dcom.sun.management.jmxremote.registry.ssl=true\n" +
                            "-Djavax.net.ssl.keyStore=" + OPERATOR_KEYSTORE_MOUNT_PATH + "/" + OPERATOR_KEYSTORE + "\n" +
                            "-Djavax.net.ssl.keyStorePassword="+OPERATOR_KEYPASS + "\n" +
                            "-Djavax.net.ssl.keyStoreType=PKCS12\n" +
                            "-Djavax.net.ssl.trustStore=" + this.authorityManager.getPublicCaMountPath()+ "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + "\n" +
                            "-Djavax.net.ssl.trustStorePassword=" + this.authorityManager.getCaTrustPass() + "\n" +
                            "-Djavax.net.ssl.trustStoreType=PKCS12");
        } else {
            // local JMX, clear + no auth
            configMapVolumeAddFile(configMap, volumeSource, "jvm.options.d/001-jmx.options",
                    "-Dcassandra.jmx.remote.port=" + dataCenterSpec.getJmxPort() + "\n" +
                            "-Dcom.sun.management.jmxremote.rmi.port=" + dataCenterSpec.getJmxPort() + "\n" +
                            "-Dcom.sun.management.jmxremote.authenticate=true\n" +
                            "-Dcom.sun.management.jmxremote.password.file=/etc/cassandra/jmxremote.password\n" +
                            "-Djava.rmi.server.hostname=127.0.0.1\n" +
                            "-XX:+DisableAttachMechanism");
        }

        // Add jdb transport socket
        if (dataCenterSpec.getJdbPort() > 0) {
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/001-cassandra-jdb.sh",
                    "JVM_OPTS=\"${JVM_OPTS} -Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=" + dataCenterSpec.getJdbPort() + "\"");
        }
        
        // this does not work with elassandra because it needs to run as root. It has been moved to the init container
        // tune ulimits
        // configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-cassandra-limits.sh",
        //        "ulimit -l unlimited\n" // unlimited locked memory
        //);
        
        // heap size and GC settings
        // TODO: tune
        {
            final long coreCount = 4; // TODO: not hard-coded
            final long memoryLimit = dataCenterSpec.getResources().getLimits().get("memory").getNumber().longValue();
            
            // same as stock cassandra-env.sh
            final long jvmHeapSize = Math.max(
                    Math.min(memoryLimit / 2, GB),
                    Math.min(memoryLimit / 4, 8 * GB)
            );
            
            final long youngGenSize = Math.min(
                    MB * coreCount,
                    jvmHeapSize / 4
            );
            
            final boolean useG1GC = (jvmHeapSize > 8 * GB);
            
            final StringWriter writer = new StringWriter();
            try (final PrintWriter printer = new PrintWriter(writer)) {
                printer.format("-Xms%d%n", jvmHeapSize); // min heap size
                printer.format("-Xmx%d%n", jvmHeapSize); // max heap size
                
                // copied from stock jvm.options
                if (useG1GC) {
                    printer.println("-XX:+UseG1GC");
                    printer.println("-XX:G1RSetUpdatingPauseTimePercent=5");
                    printer.println("-XX:MaxGCPauseMillis=500");

                    if (jvmHeapSize > 16 * GB) {
                        printer.println("-XX:InitiatingHeapOccupancyPercent=70");
                    }

                    // TODO: tune -XX:ParallelGCThreads, -XX:ConcGCThreads
                } else {
                    printer.format("-Xmn%d%n", youngGenSize); // young gen size
                    
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
        
        // TODO: maybe tune -Dcassandra.available_processors=number_of_processors - Wait till we build C* for Java 11
        // not sure if k8s exposes the right number of CPU cores inside the container
        
        // strapdata ssl support
        addSslConfig(configMap, volumeSource);
        
        //strapdata authentication support
        addAuthenticationConfig(configMap, volumeSource);
        
        // elasticsearch config support
        addElasticsearchConfig(configMap, volumeSource);

        k8sResourceUtils.createOrReplaceNamespacedConfigMap(configMap);
        
        final String configMapFingerprint = DigestUtils.sha1Hex(appsApi.getApiClient().getJSON().getGson().toJson(configMap.getData()));
        
        return Tuple.of(new ConfigMapVolumeMount("operator-spec-config-volume", "/tmp/operator-spec-config", volumeSource), configMapFingerprint);
    }


    
    private void addSslConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        if (!dataCenterSpec.getSsl()) {
            return;
        }
        
        final Map<String, Object> cassandraConfig = new HashMap<>();
        
        cassandraConfig.put("server_encryption_options", ImmutableMap.builder()
                .put("internode_encryption", "all")
                .put("keystore", OPERATOR_KEYSTORE_MOUNT_PATH + "/keystore.p12")
                .put("keystore_password", "changeit")
                .put("truststore", this.authorityManager.getPublicCaMountPath() + "/truststore.p12")
                .put("truststore_password", this.authorityManager.getCaTrustPass())
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
                .put("truststore", this.authorityManager.getPublicCaMountPath() + "/truststore.p12")
                .put("truststore_password", "changeit")
                .put("protocol", "TLSv1.2")
                .put("store_type", "PKCS12")
                .put("algorithm", "SunX509")
                .put("require_client_auth", false)
                //.put("cipher_suites", ImmutableList.of("TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"))
                .build()
        );

        configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-ssl.yaml", toYamlString(cassandraConfig));
    }
    
    private void addAuthenticationConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        switch (dataCenterSpec.getAuthentication()) {
            case NONE:
                break;
            case CASSANDRA:
                configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-authentication.yaml",
                        toYamlString(ImmutableMap.of(
                                "authenticator", "PasswordAuthenticator",
                                "authorizer", "CassandraAuthorizer")));
                break;
            case LDAP:
                configMapVolumeAddFile(configMap, volumeSource, "cassandra.yaml.d/002-authentication.yaml",
                        toYamlString(ImmutableMap.of(
                                "authenticator", "com.strapdata.cassandra.ldap.LDAPAuthenticator",
                                "authorizer", "CassandraAuthorizer",
                                "role_manager", "com.strapdata.cassandra.ldap.LDAPRoleManager")));
                //TODO: Add ldap.properties + ldap.pem +
                // -Dldap.properties.file=/usr/share/cassandra/conf/ldap.properties
                // -Dcom.sun.jndi.ldap.object.disableEndpointIdentification=true"
                break;
        }
    }
    
    private void addElasticsearchConfig(V1ConfigMap configMap, V1ConfigMapVolumeSource volumeSource) {
        final Enterprise enterprise = dataCenterSpec.getEnterprise();
        if (enterprise.getEnabled()) {
            final Map<String, Object> esConfig = new HashMap<>();

            esConfig.put("jmx", ImmutableMap.of(
                    "enabled", enterprise.getJmx()
            ));
            
            esConfig.put("https", ImmutableMap.of(
                    "enabled", enterprise.getHttps()
            ));
            
            esConfig.put("ssl", ImmutableMap.of("transport", ImmutableMap.of(
                    "enabled", enterprise.getSsl()
            )));
            
            if (enterprise.getAaa() == null) {
                esConfig.put("aaa", ImmutableMap.of("enabled", false));
            } else {
                esConfig.put("aaa", ImmutableMap.of(
                        "enabled", enterprise.getAaa().getEnabled(),
                        "audit", ImmutableMap.of("enabled", enterprise.getAaa().getAudit())
                ));
            }
            
            esConfig.put("cbs", ImmutableMap.of(
                    "enabled", enterprise.getCbs()
            ));
            
            configMapVolumeAddFile(configMap, volumeSource, "elasticsearch.yml.d/002-enterprise.yaml", toYamlString(esConfig));
            
            configMapVolumeAddFile(configMap, volumeSource, "cassandra-env.sh.d/002-enterprise.sh",
                    "JVM_OPTS=\"$JVM_OPTS -Dcassandra.custom_query_handler_class=org.elassandra.index.EnterpriseElasticQueryHandler\"");
            // TODO: override com exporter in cassandra-env.sh.d/001-cassandra-exporter.sh
        }

        // add elassandra datacenter.group config
        if (dataCenterSpec.getDatacenterGroup() != null) {
            final Map<String, Object> esConfig = new HashMap<>();
            esConfig.put("datacenter", ImmutableMap.of("group", dataCenterSpec.getDatacenterGroup()));
            configMapVolumeAddFile(configMap, volumeSource, "elasticsearch.yml.d/003-datacentergroup.yaml", toYamlString(esConfig));
        }
    }

    /**
     * Create a headless seed service per rack.
     * @param rack
     * @return
     * @throws ApiException
     */
    // No used any more
    private V1Service createOrReplaceSeedService(String rack) throws ApiException {
        final V1ObjectMeta serviceMetadata = dataCenterChildObjectMetadata(OperatorNames.seedsService(dataCenter))
                // tolerate-unready-endpoints - allow the seed provider can discover the other seeds (and itself) before the readiness-probe gives the green light
                .putAnnotationsItem("service.alpha.kubernetes.io/tolerate-unready-endpoints", "true");
        
        final V1Service service = new V1Service()
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
        k8sResourceUtils.createOrReplaceNamespacedService(service);
        return service;
    }

    /**
     * The NodePort service has a DNS name and redirect to STS seed pods
     * see https://stackoverflow.com/questions/46456239/how-to-expose-a-headless-service-for-a-statefulset-externally-in-kubernetes
     */
    private V1Service createOrReplaceExternalNodesService() throws ApiException {
        final V1Service service = new V1Service()
                .metadata(dataCenterChildObjectMetadata(OperatorNames.externalService(dataCenter)))
                .spec(new V1ServiceSpec()
                        .type("NodePort")
                        .addPortsItem(new V1ServicePort().name("internode").port(dataCenterSpec.getSsl() ? dataCenterSpec.getSslStoragePort() : dataCenterSpec.getStoragePort()))
                        .addPortsItem(new V1ServicePort().name("cql").port(dataCenterSpec.getNativePort()))
                        .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                        .addPortsItem(new V1ServicePort().name("jmx").port(dataCenterSpec.getJmxPort()))
                        // select any available pod in the DC, which is good only if internal seed service is good !
                        .selector(ImmutableMap.of(OperatorLabels.DATACENTER, dataCenter.getSpec().getDatacenterName()))
                );
        k8sResourceUtils.createOrReplaceNamespacedService(service);
        return service;
    }

    private void createOrReplaceNodesService() throws ApiException {
        final V1ObjectMeta serviceMetadata = dataCenterChildObjectMetadata(OperatorNames.nodesService(dataCenter));
        
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
        
        k8sResourceUtils.createOrReplaceNamespacedService(service);
    }
    
    private void createOrReplaceElasticsearchService() throws ApiException {
        final V1Service service = new V1Service()
                .metadata(dataCenterChildObjectMetadata(OperatorNames.elasticsearchService(dataCenter)))
                .spec(new V1ServiceSpec()
                        .type("ClusterIP")
                        .addPortsItem(new V1ServicePort().name("elasticsearch").port(9200))
                        .selector(dataCenterLabels)
                );
        k8sResourceUtils.createOrReplaceNamespacedService(service);
    }
    
    private void createOrScaleAllStatefulsets(final TreeMap<String, V1StatefulSet> newStatefulSetMap,
                                              final TreeMap<String, V1StatefulSet> existingStatefulSetMap) throws Exception {
        
        
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
        statefulSetUpdateAction.updateNextStatefulSet(dataCenter, newStatefulSetMap, existingStatefulSetMap);
    }
    
    private void createKeystoreIfNotExists() throws Exception {
        final V1ObjectMeta certificatesMetadata = dataCenterChildObjectMetadata(OperatorNames.keystore(dataCenter));
        
        // check if secret exists
        try {
            coreApi.readNamespacedSecret(certificatesMetadata.getName(), certificatesMetadata.getNamespace(), null, null, null);
            return; // do not create the certificates if already exists
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        
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
        
        coreApi.createNamespacedSecret(dataCenterMetadata.getNamespace(), certificatesSecret, null, null, null);
    }

    /**
     * Creates and stores credentials in a K8S secret if not exists
     * @return admin login and password for RC files
     * @throws ApiException
     */
    private Tuple2<String, String> createClusterSecretIfNotExists() throws ApiException {
        final V1ObjectMeta secretMetadata = clusterChildObjectMetadata(OperatorNames.clusterSecret(dataCenter));
        // check if secret exists
        try {
            V1Secret secret = coreApi.readNamespacedSecret(secretMetadata.getName(), secretMetadata.getNamespace(), null, null, null);
            byte[] passBytes = secret.getData().get(String.format(Locale.ROOT, "cassandra.%s_password", "admin"));
            return new Tuple2<>("admin", new String(passBytes));
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        
        // because this is created once, we should put things even if we don't need it yet (e.g shared secret if AAA is disabled)
        String adminPassword = UUID.randomUUID().toString();
        final V1Secret secret = new V1Secret()
                .metadata(secretMetadata)
                // replace the default cassandra password
                .putStringDataItem("cassandra.cassandra_password", UUID.randomUUID().toString())
                // strapkop role is used by the operator and sidecar
                .putStringDataItem("cassandra.strapkop_password", UUID.randomUUID().toString())
                // admin is intended to be distributed to human administrator, so the credentials lifecycle is decoupled
                .putStringDataItem("cassandra.admin_password", adminPassword)
                // password used by reaper to access its cassandra backend
                .putStringDataItem("cassandra.reaper_password", UUID.randomUUID().toString())
                
                // password used to access reaper webui and api
                .putStringDataItem("reaper.admin_password", UUID.randomUUID().toString())
                
                // cassandra JMX password, mounted as /etc/cassandra/jmxremote.password
                .putStringDataItem("cassandra.jmx_password", UUID.randomUUID().toString())
                // elassandra-enterprise shared secret is intended to be mounted as a config fragment
                .putStringDataItem("shared-secret.yaml", "aaa.shared_secret: " + UUID.randomUUID().toString());

        coreApi.createNamespacedSecret(dataCenterMetadata.getNamespace(), secret, null, null, null);
        return new Tuple2<>("admin", adminPassword);
    }

    /**
     * Generate rc files as secret (.curlrc and .cassandra/cqlshrc + .cassandra/nodetool-ssl.properties)
     * TODO: avoid generation on each reconciliation
     * @param username
     * @param password
     */
    private void createOrUpdateRcFileSecret(String username, String password) throws ApiException {
        String cqlshrc = "";
        String curlrc = "";

        if (dataCenterSpec.getSsl()) {
            cqlshrc +=  "[connection]\n" +
                            "factory = cqlshlib.ssl.ssl_transport_factory\n" +
                            "port = " + dataCenterSpec.getNativePort() + "\n" +
                            "ssl = true\n" +
                            "\n" +
                            "[ssl]\n" +
                            "certfile = " + this.authorityManager.getPublicCaMountPath() + "/cacert.pem\n" +
                            "validate = true\n";

            if (Optional.ofNullable(dataCenterSpec.getEnterprise()).map(Enterprise::getSsl).orElse(false)) {
                curlrc += "cacert = " + this.authorityManager.getPublicCaMountPath() + "/cacert.pem\n";
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

        final V1ObjectMeta secretMetadata = clusterChildObjectMetadata(OperatorNames.clusterRcFilesSecret(dataCenter));
        final V1Secret secret = new V1Secret()
                .metadata(secretMetadata)
                .putStringDataItem("cqlshrc", cqlshrc)
                .putStringDataItem("curlrc", curlrc);
        if (dataCenterSpec.getSsl())
            secret.putStringDataItem("nodetool-ssl.properties", nodetoolSsl());

        k8sResourceUtils.createOrReplaceNamespacedSecret(secret);
    }

    public String nodetoolSsl() {
        return "-Djavax.net.ssl.trustStore=" + this.authorityManager.getPublicCaMountPath() + "/" + AuthorityManager.SECRET_TRUSTSTORE_P12 + " " +
                "-Djavax.net.ssl.trustStorePassword=" + this.authorityManager.getCaTrustPass() + " " +
                "-Djavax.net.ssl.trustStoreType=PKCS12 " +
                "-Dcom.sun.management.jmxremote.registry.ssl=true";
    }
}