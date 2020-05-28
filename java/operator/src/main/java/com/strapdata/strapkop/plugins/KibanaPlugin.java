package com.strapdata.strapkop.plugins;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.Authentication;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import com.strapdata.strapkop.model.k8s.datacenter.KibanaSpace;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.models.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manage kibana deployments (one deployment per space, with a dedicated C* role named kibana-[space]).
 * More usefull if strapkop is avaiable as a commercial operator in the google k8s marketplace
 */
@Singleton
public class KibanaPlugin extends AbstractPlugin {

    public static final String KIBANA_SPACE_LABEL = "kibana-space";

    public KibanaPlugin(final ApplicationContext context,
                        K8sResourceUtils k8sResourceUtils,
                        AuthorityManager authorityManager,
                        CoreV1Api coreApi,
                        AppsV1Api appsApi,
                        OperatorConfig operatorConfig,
                        MeterRegistry meterRegistry) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
    }

    @Override
    public Map<String, String> deploymentLabelSelector(DataCenter dc) {
        return ImmutableMap.of(OperatorLabels.APP, "kibana",
                OperatorLabels.PARENT, dc.getMetadata().getName(),
                OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR);
    }

    /**
     * Default space with empty name
     */
    private static final KibanaSpace DEFAULT_KIBANA_SPACE = new KibanaSpace().withName("").withKeyspaces(ImmutableSet.of("_kibana"));

    private Map<String, KibanaSpace> getKibanaSpaces(final DataCenter dataCenter) {
        Map<String, KibanaSpace> spaces = dataCenter.getSpec().getElasticsearch().getKibana().getSpaces().stream().collect(Collectors.toMap(KibanaSpace::getName, Function.identity()));
        return (spaces.size() == 0) ? ImmutableMap.of(DEFAULT_KIBANA_SPACE.getName(), DEFAULT_KIBANA_SPACE) : spaces;
    }

    @Override
    public Completable syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        Integer version = dataCenter.getSpec().getElasticsearch().getKibana().getVersion();
        for (KibanaSpace kibana : getKibanaSpaces(dataCenter).values()) {
            cqlKeyspaceManager.addIfAbsent(dataCenter, kibana.keyspace(version), () -> new CqlKeyspace()
                    .withName(kibana.keyspace(version))
                    .withRf(3)
            );
        }
        return Completable.complete();
    }

    /**
     * For each kibana space, creates a kibana k8s secret if not exists, and register a CqlRole.
     * @param cqlRoleManager
     * @param dataCenter
     * @return
     * @throws ApiException
     */
    @Override
    public Completable syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) throws ApiException {
        Integer version = dataCenter.getSpec().getElasticsearch().getKibana().getVersion();
        List<CompletableSource> todoList = new ArrayList<>();
        for (KibanaSpace kibana : getKibanaSpaces(dataCenter).values()) {
            V1ObjectMeta v1SecretMeta = new V1ObjectMeta()
                    .name(OperatorNames.clusterChildObjectName("%s-" + kibana.role(), dataCenter))
                    .namespace(dataCenter.getMetadata().getNamespace())
                    .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                    .labels(OperatorLabels.cluster(dataCenter.getSpec().getClusterName()));
            todoList.add(k8sResourceUtils.readOrCreateNamespacedSecret(v1SecretMeta, () -> {
                V1Secret secret = new V1Secret().metadata(v1SecretMeta).type("Opaque");
                secret.putStringDataItem("kibana.kibana_password", UUID.randomUUID().toString());
                logger.debug("Created new cluster secret={}/{}", v1SecretMeta.getName(), v1SecretMeta.getNamespace());
                return secret;
            }).map(secret -> {
                cqlRoleManager.addIfAbsent(dataCenter, kibana.keyspace(version), () -> new CqlRole()
                        .withUsername(kibana.role())
                        .withSecretKey("kibana.kibana_password")
                        .withSecretNameProvider(dc -> OperatorNames.clusterChildObjectName("%s-" + kibana.role(), dc))
                        .withReconcilied(false)
                        .withSuperUser(true)
                        .withLogin(true)
                        .withGrantStatements(
                                ImmutableList.of(
                                        String.format(Locale.ROOT, "GRANT ALL PERMISSIONS ON KEYSPACE \"%s\" TO %s", kibana.keyspace(version), kibana.role()),
                                        String.format(Locale.ROOT, "INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','cluster:monitor/.*','.*')", kibana.index(version)),
                                        String.format(Locale.ROOT, "INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','indices:.*','.*')", kibana.index(version))
                                )
                        )
                );
                return secret;
            }).ignoreElement());
        }
        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]));
    }

    public static String kibanaName(DataCenter dataCenter, KibanaSpace space) {
        return "elassandra-" + dataCenter.getSpec().getClusterName() + "-" + space.name();
    }

    public static String kibanaNameDc(DataCenter dataCenter, KibanaSpace space) {
        return "elassandra-" + dataCenter.getSpec().getClusterName() + "-" + dataCenter.getSpec().getDatacenterName() + "-kibana" + (space.name().length() > 0 ? "-" : "") + space.name();
    }


    public static Map<String, String> kibanaLabels(DataCenter dataCenter) {
        final Map<String, String> labels = new HashMap<>(OperatorLabels.datacenter(dataCenter));
        labels.put(OperatorLabels.APP, "kibana"); // overwrite label app
        return labels;
    }

    public static Map<String, String> kibanaSpaceLabels(DataCenter dataCenter, String kibanaSpaceName) {
        final Map<String, String> labels = new HashMap<>(OperatorLabels.datacenter(dataCenter));
        labels.put(OperatorLabels.APP, "kibana"); // overwrite label app
        labels.put(KIBANA_SPACE_LABEL, kibanaSpaceName); // overwrite label app
        return labels;
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getElasticsearch().getEnabled() && dataCenter.getSpec().getElasticsearch().getKibana().getEnabled();
    }


    @Override
    public Single<Boolean> reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        logger.trace("datacenter={} kibana.spec={}", dataCenter.id(), dataCenter.getSpec().getElasticsearch().getKibana());
        Set<String> deployedKibanaSpaces = dataCenter.getStatus().getKibanaSpaceNames();
        Map<String, KibanaSpace> desiredKibanaMap = getKibanaSpaces(dataCenter);

        return this.listDeployments(dataCenter)
                .flatMap(deployments -> {
                    boolean kibanaEnabled = dataCenter.getSpec().getElasticsearch().getKibana() != null && dataCenter.getSpec().getElasticsearch().getKibana().getEnabled();
                    logger.debug("datacenter={} enabled={} parked={} deployments.size={}",
                            dataCenter.id(), kibanaEnabled, dataCenter.getSpec().isParked(), deployments.size());
                    if ((kibanaEnabled == false || desiredKibanaMap.size() == 0 || dataCenter.getSpec().isParked())) {
                        return delete(dataCenter)
                                .map(s -> {
                                    dataCenter.getStatus().setKibanaSpaceNames(new HashSet<>());
                                    return true;
                                });
                    }

                    Set<String> deletedSpaces = Sets.difference(deployedKibanaSpaces, desiredKibanaMap.keySet());
                    Completable deleteCompletable = deletedSpaces.isEmpty() ?
                            Completable.complete() :
                            io.reactivex.Observable.fromIterable(deletedSpaces)
                                    .flatMapCompletable(spaceToDelete -> {
                                        logger.debug("Deleting kibana space={}", spaceToDelete);
                                        dataCenter.getStatus().getKibanaSpaceNames().remove(spaceToDelete);
                                        return deleteSpace(dataCenter, spaceToDelete);
                                    });

                    Set<String> newSpaces = Sets.difference(getKibanaSpaces(dataCenter).keySet(), deployedKibanaSpaces);
                    Completable createCompletable = newSpaces.isEmpty() ?
                            Completable.complete() :
                            io.reactivex.Observable.fromIterable(getKibanaSpaces(dataCenter).values().stream().filter(k -> newSpaces.contains(k.getName())).collect(Collectors.toList()))
                                    .flatMapCompletable(kibanaSpace -> {
                                        logger.debug("Adding kibana space={}", kibanaSpace);
                                        dataCenter.getStatus().getKibanaSpaceNames().add(kibanaSpace.getName());
                                        return createOrReplaceKibanaObjects(dataCenter, kibanaSpace);
                                    });


                    // update kibana deployment spec replicas if needed
                    for (V1Deployment deployment : deployments) {
                        String kibanaSpaceName = deployment.getMetadata().getLabels().get(KIBANA_SPACE_LABEL);
                        KibanaSpace kibanaSpace = desiredKibanaMap.get(kibanaSpaceName);
                        int replicas = kibanaReplicas(dataCenter, kibanaSpace);
                        if (kibanaSpaceName != null &&
                                !newSpaces.contains(kibanaSpaceName) &&
                                deployment.getSpec().getReplicas() != replicas) {
                            logger.debug("datacenter={} updating deployment={} replicas={}", dataCenter.id(), deployment.getMetadata().getName(), replicas);
                            deployment.getSpec().setReplicas(replicas);
                            createCompletable = createCompletable.andThen(
                                    k8sResourceUtils.updateNamespacedDeployment(deployment).ignoreElement()
                            );
                        }
                    }

                    return deleteCompletable.andThen(createCompletable)
                            .toSingleDefault(!deletedSpaces.isEmpty() || !newSpaces.isEmpty())
                            .map(s -> {
                                dataCenter.getStatus().setKibanaSpaceNames(getKibanaSpaces(dataCenter).keySet());
                                return s;
                            });
                });
    }

    @Override
    public Single<Boolean> delete(final DataCenter dataCenter) throws ApiException {
        final String kibanaLabelsSelector = OperatorLabels.toSelector(kibanaLabels(dataCenter));
        return Completable.mergeArray(new Completable[]{
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, kibanaLabelsSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, kibanaLabelsSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, kibanaLabelsSelector)
        }).toSingleDefault(false);
    }

    public Completable deleteSpace(final DataCenter dataCenter, String kibanaSpaceName) {
        final String kibanaSpaceLabelSelector = OperatorLabels.toSelector(kibanaSpaceLabels(dataCenter, kibanaSpaceName));
        return Completable.mergeArray(new Completable[]{
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, kibanaSpaceLabelSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, kibanaSpaceLabelSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, kibanaSpaceLabelSelector)
        });
    }


    /**
     * @return The number of kibana pods depending on ReaperStatus
     */
    private int kibanaReplicas(final DataCenter dataCenter, KibanaSpace kibanaSpace) {
        if (dataCenter.getSpec().isParked())
            return 0;

        Integer version = dataCenter.getSpec().getElasticsearch().getKibana().getVersion();
        return (dataCenter.getStatus().getPhase().isRunning() &&
                dataCenter.getStatus().getBootstrapped() == true &&
                dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().contains(kibanaSpace.keyspace(version))) ? kibanaSpace.getReplicas() : 0;
    }


    public Completable createOrReplaceKibanaObjects(final DataCenter dataCenter, KibanaSpace space) throws
            ApiException, StrapkopException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final Integer version = dataCenter.getSpec().getElasticsearch().getKibana().getVersion();

        final Map<String, String> labels = kibanaSpaceLabels(dataCenter, space.getName());

        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(kibanaNameDc(dataCenter, space))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());

        final V1Container container = new V1Container();

        final V1PodSpec podSpec = new V1PodSpec()
                .serviceAccountName(dataCenterSpec.getAppServiceAccount())
                .addContainersItem(container);

        final V1Deployment deployment = new V1Deployment()
                .metadata(meta)
                .spec(new V1DeploymentSpec()
                        // delay the creation of the reaper pod, after we have created the reaper_db keyspace
                        .replicas(kibanaReplicas(dataCenter, space))
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(labels))
                                .spec(podSpec)
                        )
                );

        if (dataCenterSpec.getPriorityClassName() != null) {
            podSpec.setPriorityClassName(dataCenterSpec.getPriorityClassName());
        }

        if (dataCenterSpec.getImagePullSecrets() != null) {
            for (String secretName : dataCenterSpec.getImagePullSecrets()) {
                final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secretName);
                podSpec.addImagePullSecretsItem(pullSecret);
            }
        }

        container
                .name("kibana")
                .image(dataCenter.getSpec().getElasticsearch().getKibana().getImage())
                .terminationMessagePolicy("FallbackToLogsOnError")
                .addPortsItem(new V1ContainerPort()
                        .name("kibana")
                        .containerPort(5601)
                        .protocol("TCP")
                )
                .livenessProbe(new V1Probe()
                        .tcpSocket(new V1TCPSocketAction().port(new IntOrString(5601)))
                        .initialDelaySeconds(30)
                        .timeoutSeconds(10)
                )
                .readinessProbe(new V1Probe()
                        .tcpSocket(new V1TCPSocketAction().port(new IntOrString(5601)))
                        .initialDelaySeconds(30)
                        .periodSeconds(10)
                        .timeoutSeconds(10)
                        .successThreshold(5)
                )
                /* HTTP liveness require a login/password
                .livenessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction().path("/status").port(new IntOrString(5601)))
                        .initialDelaySeconds(30)
                        .timeoutSeconds(10)
                )
                .readinessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction().path("/status").port(new IntOrString(5601)))
                        .initialDelaySeconds(30)
                        .periodSeconds(10)
                        .timeoutSeconds(10)
                        .successThreshold(5)
                )
                */
                .addEnvItem(new V1EnvVar()
                        .name("ELASTICSEARCH_URL")
                        .value((Boolean.TRUE.equals(dataCenterSpec.getElasticsearch().getEnterprise().getHttps()) ? "https://" : "http://") +
                                OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local:" + dataCenterSpec.getElasticsearch().getHttpPort())
                )
                .addEnvItem(new V1EnvVar()
                        .name("KIBANA_INDEX")
                        .value(space.index(null))
                )
                .addEnvItem(new V1EnvVar().name("LOGGING_VERBOSE").value("true"))
        //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_ENABLED").value("false"))
        //.addEnvItem(new V1EnvVar().name("XPACK_SECURITY_ENABLED").value("false"))
        //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_UI_CONTAINER_ELASTICSEARCH_ENABLED").value("false"))
        ;

        // add NODE_OPTIONS for memory tunning
        if (!Strings.isNullOrEmpty(space.getNodeOptions())) {
            container.addEnvItem(new V1EnvVar().name("NODE_OPTIONS").value(space.getNodeOptions()));
        }


        // kibana with cassandra authentication
        if (!Objects.equals(dataCenterSpec.getCassandra().getAuthentication(), Authentication.NONE)) {
            String kibanaSecretName = kibanaName(dataCenter, space);

            container
                    .addEnvItem(new V1EnvVar()
                            .name("ELASTICSEARCH_USERNAME")
                            .value(space.role())
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("ELASTICSEARCH_PASSWORD")
                            .valueFrom(new V1EnvVarSource()
                                    .secretKeyRef(new V1SecretKeySelector()
                                            .name(kibanaSecretName)
                                            .key("kibana.kibana_password")
                                    )
                            )
                    );


        }

        // kibana with cassandra ssl on native port
        if (Boolean.TRUE.equals(dataCenterSpec.getCassandra().getSsl())) {
            podSpec.addVolumesItem(new V1Volume()
                    .name("truststore")
                    .secret(new V1SecretVolumeSource()
                            .secretName(authorityManager.getPublicCaSecretName())
                    )
            );
            container
                    .addEnvItem(new V1EnvVar()
                            .name("ELASTICSEARCH_SSL_VERIFICATIONMODE")
                            .value("none")
                    )
                    /*
                    .addEnvItem(new V1EnvVar()
                            .name("ELASTICSEARCH_SSL_CERTIFICATEAUTHORITIES")
                            .value("[ \"/truststore/cacert.pem\" ]")
                    )
                    */
                    .addVolumeMountsItem(new V1VolumeMount()
                            .mountPath("/truststore")
                            .name("truststore")
                    );
        }

        return k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment)
                .flatMap(d -> {
                    return k8sResourceUtils.createOrReplaceNamespacedService(new V1Service()
                            .metadata(meta)
                            .spec(new V1ServiceSpec()
                                    .type("ClusterIP")
                                    .addPortsItem(new V1ServicePort().name("kibana").port(5601))
                                    .selector(labels)
                            ));
                })
                .flatMap(s -> {
                    if (!Strings.isNullOrEmpty(dataCenterSpec.getElasticsearch().getKibana().getIngressSuffix())) {
                        String kibanaHost = space.name() + "-" + dataCenterSpec.getElasticsearch().getKibana().getIngressSuffix();
                        logger.info("Creating kibana ingress for host={}", kibanaHost);
                        final V1ObjectMeta ingressMeta = new V1ObjectMeta()
                                .name(kibanaNameDc(dataCenter, space))
                                .namespace(dataCenterMetadata.getNamespace())
                                .labels(labels)
                                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
                        if (dataCenterSpec.getElasticsearch().getKibana().getIngressAnnotations() != null && !dataCenterSpec.getElasticsearch().getKibana().getIngressAnnotations().isEmpty()) {
                            dataCenterSpec.getElasticsearch().getKibana().getIngressAnnotations().entrySet().stream()
                                    .map(e -> ingressMeta.putAnnotationsItem(e.getKey(), e.getValue()));
                        }
                        final ExtensionsV1beta1Ingress ingress = new ExtensionsV1beta1Ingress()
                                .metadata(ingressMeta)
                                .spec(new ExtensionsV1beta1IngressSpec()
                                        .addRulesItem(new ExtensionsV1beta1IngressRule()
                                                .host(kibanaHost)
                                                .http(new ExtensionsV1beta1HTTPIngressRuleValue()
                                                        .addPathsItem(new ExtensionsV1beta1HTTPIngressPath()
                                                                .path("/")
                                                                .backend(new ExtensionsV1beta1IngressBackend()
                                                                        .serviceName(meta.getName())
                                                                        .servicePort(new IntOrString(5601)))
                                                        ))
                                        )
                                        .addTlsItem(new ExtensionsV1beta1IngressTLS()
                                                .addHostsItem(kibanaHost)
                                        )
                                );
                        return k8sResourceUtils.createOrReplaceNamespacedIngress(ingress).map(i -> s);
                    }
                    return Single.just(s);
                })
                .flatMap(s -> createKibanaSecretIfNotExists(dataCenter, space))
                .flatMapCompletable(s2 -> Completable.fromCallable(new Callable<V1Deployment>() {
                    /**
                     * Computes a result, or throws an exception if unable to do so.
                     *
                     * @return computed result
                     * @throws Exception if unable to compute a result
                     */
                    @Override
                    public V1Deployment call() throws Exception {
                        // abort deployment replacement if it is already up to date (according to the annotation datacenter-generation and to spec.replicas)
                        // this is important because otherwise it generate a "larsen" : deployment replace -> k8s event -> reconciliation -> deployment replace...
                        try {
                            final V1Deployment existingDeployment = appsApi.readNamespacedDeployment(meta.getName(), meta.getNamespace(), null, null, null);
                            final String datacenterGeneration = existingDeployment.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION);

                            if (datacenterGeneration == null) {
                                throw new StrapkopException(String.format("kibana deployment %s miss the annotation datacenter-generation", meta.getName()));
                            }

                            if (Objects.equals(Long.parseLong(datacenterGeneration), dataCenterMetadata.getGeneration()) &&
                                    Objects.equals(existingDeployment.getSpec().getReplicas(), deployment.getSpec().getReplicas())) {
                                return existingDeployment;
                            }
                        } catch (ApiException e) {
                            if (e.getCode() != 404) {
                                throw e;
                            }
                        }

                        return k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment).blockingGet();
                    }
                }));
    }

    private Single<V1Secret> createKibanaSecretIfNotExists(DataCenter dataCenter, KibanaSpace kibanaSpace) throws ApiException {
        String kibanaSecretName = kibanaName(dataCenter, kibanaSpace);
        final V1ObjectMeta secretMetadata = new V1ObjectMeta()
                .name(kibanaSecretName)
                .namespace(dataCenter.getMetadata().getNamespace())
                .labels(OperatorLabels.cluster(dataCenter.getSpec().getClusterName()));

        return this.k8sResourceUtils.readOrCreateNamespacedSecret(secretMetadata, () -> {
            logger.debug("Creating kibana secret name={}", kibanaSecretName);
            return new V1Secret()
                    .metadata(secretMetadata)
                    // replace the default cassandra password
                    .putStringDataItem("kibana.kibana_password", UUID.randomUUID().toString());
        });
    }

}
