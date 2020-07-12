/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.plugins;

import com.google.common.base.Strings;
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
import com.strapdata.strapkop.model.k8s.datacenter.*;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
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
    private static final KibanaSpace DEFAULT_KIBANA_SPACE = new KibanaSpace()
            .withName("")
            .withVersion(1)
            .withKeyspaces(ImmutableSet.of("_kibana_1"));

    private Map<String, KibanaSpace> getKibanaSpaces(final DataCenter dataCenter) {
        List<KibanaSpace> spaces = dataCenter.getSpec().getKibana().getSpaces();
        if (spaces.isEmpty())
            spaces.add(DEFAULT_KIBANA_SPACE);
        return spaces.stream().collect(Collectors.toMap(kibanaSpace -> kibanaSpace.name(), Function.identity()));
    }

    @Override
    public Completable syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        for (KibanaSpace kibanaSpace : getKibanaSpaces(dataCenter).values()) {
            Integer version = kibanaSpace.getVersion();
            cqlKeyspaceManager.addIfAbsent(dataCenter, kibanaSpace.keyspace(version), () -> new CqlKeyspace()
                    .withName(kibanaSpace.keyspace(version))
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
        logger.debug("datacenter={} sync kibana roles for spaces={}", getKibanaSpaces(dataCenter));
        List<CompletableSource> todoList = new ArrayList<>();
        for (KibanaSpace kibanaSpace : getKibanaSpaces(dataCenter).values()) {
            Integer version = kibanaSpace.getVersion();

            // Warning: no ownerReference here because the secret maybe used by other DCs in the same namespace
            V1ObjectMeta v1SecretMeta = new V1ObjectMeta()
                    .name(OperatorNames.clusterChildObjectName("%s-" + kibanaSpace.role(), dataCenter))
                    .namespace(dataCenter.getMetadata().getNamespace())
                    .labels(OperatorLabels.cluster(dataCenter.getSpec().getClusterName()));

            todoList.add(k8sResourceUtils.readOrCreateNamespacedSecret(v1SecretMeta, () -> {
                V1Secret secret = new V1Secret().metadata(v1SecretMeta).type("Opaque");
                secret.putStringDataItem("kibana.kibana_password", UUID.randomUUID().toString());
                logger.debug("Created new cluster secret={}/{}", v1SecretMeta.getName(), v1SecretMeta.getNamespace());
                return secret;
            }).map(secret -> {
                logger.debug("datacenter={} Adding kibana role={} for space={}", dataCenter.id(), kibanaSpace.role(), kibanaSpace.name());
                cqlRoleManager.addIfAbsent(dataCenter, kibanaSpace.keyspace(version), () -> new CqlRole()
                        .withUsername(kibanaSpace.role())
                        .withSecretKey("kibana.kibana_password")
                        .withSecretNameProvider(dc -> OperatorNames.clusterChildObjectName("%s-" + kibanaSpace.role(), dc))
                        .withReconcilied(false)
                        .withSuperUser(false)
                        .withLogin(true)
                        .withGrantStatements(kibanaSpace.statements())
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
        labels.put(OperatorLabels.KIBANA_SPACE_LABEL, kibanaSpaceName); // overwrite label app
        return labels;
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getElasticsearch().getEnabled() && dataCenter.getSpec().getKibana().getEnabled();
    }

    @Override
    public Single<Boolean> reconcile(DataCenterUpdateAction dataCenterUpdateAction) throws ApiException, StrapkopException {
        return listDeployments(dataCenterUpdateAction.dataCenter)
                .flatMap(deployments -> reconcile(dataCenterUpdateAction, deployments));
    }


    public Single<Boolean> reconcile(DataCenterUpdateAction dataCenterUpdateAction, List<V1Deployment> deployments) throws ApiException, StrapkopException {
        final DataCenter dataCenter = dataCenterUpdateAction.dataCenter;
        logger.trace("datacenter={} kibana.spec={}", dataCenter.id(), dataCenter.getSpec().getKibana());
        Set<String> deployedKibanaSpaces = dataCenterUpdateAction.dataCenterStatus.getKibanaSpaceNames();
        Map<String, KibanaSpace> desiredKibanaMap = getKibanaSpaces(dataCenter);

        boolean kibanaEnabled = dataCenter.getSpec().getKibana() != null && dataCenter.getSpec().getKibana().getEnabled();
        logger.debug("datacenter={} enabled={} parked={} deployments.size={}",
                dataCenter.id(), kibanaEnabled, dataCenter.getSpec().isParked(), deployments.size());

        if ((kibanaEnabled == false || desiredKibanaMap.size() == 0 || dataCenter.getSpec().isParked())) {
            return delete(dataCenter)
                    .map(s -> {
                        dataCenterUpdateAction.operation.getActions().add("Undeploying kibana");
                        dataCenterUpdateAction.dataCenterStatus.setKibanaSpaceNames(new HashSet<>());
                        return true;
                    });
        }

        Set<String> deletedSpaces = Sets.difference(deployedKibanaSpaces, desiredKibanaMap.keySet());
        Completable deleteCompletable = deletedSpaces.isEmpty() ?
                Completable.complete() :
                io.reactivex.Observable.fromIterable(deletedSpaces)
                        .flatMapCompletable(spaceToDelete -> {
                            logger.debug("Undeploying kibana space={}", spaceToDelete);
                            dataCenterUpdateAction.operation.getActions().add("Undeploying kibana space=["+spaceToDelete+"]");
                            dataCenterUpdateAction.dataCenterStatus.getKibanaSpaceNames().remove(spaceToDelete);
                            return deleteSpace(dataCenter, spaceToDelete);
                        });

        Set<String> newSpaces = Sets.difference(getKibanaSpaces(dataCenter).keySet(), deployedKibanaSpaces);
        Completable createCompletable = newSpaces.isEmpty() ?
                Completable.complete() :
                io.reactivex.Observable.fromIterable(getKibanaSpaces(dataCenter).values()
                        .stream()
                        .filter(k -> newSpaces.contains(k.name()))
                        .collect(Collectors.toList()))
                        .flatMapCompletable(kibanaSpace -> {
                            logger.debug("Deploying kibana space={}", kibanaSpace);
                            dataCenterUpdateAction.operation.getActions().add("Deploying kibana space=["+kibanaSpace.name()+"]");
                            dataCenterUpdateAction.dataCenterStatus.getKibanaSpaceNames().add(kibanaSpace.name());
                            return createOrReplaceKibanaObjects(dataCenterUpdateAction, kibanaSpace);
                        });

        for (V1Deployment deployment : deployments) {
            String kibanaSpaceName = deployment.getMetadata().getLabels().get(OperatorLabels.KIBANA_SPACE_LABEL);
            if (kibanaSpaceName == null) {
                logger.warn("datacenter={} Kibana deployment has no label={}, ignoring", dataCenter.id(), OperatorLabels.KIBANA_SPACE_LABEL);
                continue;
            }

            KibanaSpace kibanaSpace = desiredKibanaMap.get(kibanaSpaceName);
            String kibanaSpaceSpecFingerprint = kibanaSpace.fingerprint(dataCenter.getSpec().getKibana().getImage());
            String kibanaSpaceFingerprint = deployment.getMetadata().getLabels().get(OperatorLabels.KIBANA_SPACE_FINGERPRINT);
            int replicas = kibanaReplicas(dataCenter, dataCenterUpdateAction.dataCenterStatus, kibanaSpace);
            if (!kibanaSpaceSpecFingerprint.equals(kibanaSpaceFingerprint) || deployment.getSpec().getReplicas() != replicas) {
                logger.debug("datacenter={} updating deployment={} replicas={}", dataCenter.id(), deployment.getMetadata().getName(), replicas);
                dataCenterUpdateAction.operation.getActions().add("Updating kibana space=["+kibanaSpace.name()+"]");
                deployment.getSpec().setReplicas(replicas);
                createCompletable = createCompletable.andThen(createOrReplaceKibanaObjects(dataCenterUpdateAction, kibanaSpace));
            }
        }

        return deleteCompletable.andThen(createCompletable)
                .toSingleDefault(!deletedSpaces.isEmpty() || !newSpaces.isEmpty())
                .map(s -> {
                    dataCenterUpdateAction.dataCenterStatus.setKibanaSpaceNames(getKibanaSpaces(dataCenter).keySet());
                    return s;
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
    private int kibanaReplicas(final DataCenter dataCenter, final DataCenterStatus dataCenterStatus, KibanaSpace kibanaSpace) {
        if (dataCenter.getSpec().isParked())
            return 0;

        Integer version = kibanaSpace.getVersion();
        return (dataCenterStatus.getPhase().isRunning() &&
                dataCenterStatus.getBootstrapped() == true &&
                dataCenterStatus.getKeyspaceManagerStatus().getKeyspaces().contains(kibanaSpace.keyspace(version))) ? kibanaSpace.getReplicas() : 0;
    }


    public Completable createOrReplaceKibanaObjects(final DataCenterUpdateAction dataCenterUpdateAction, KibanaSpace kibanaSpace) throws
            ApiException, StrapkopException {
        final DataCenter dataCenter = dataCenterUpdateAction.dataCenter;
        final DataCenterStatus dataCenterStatus = dataCenterUpdateAction.dataCenterStatus;
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final Integer version = kibanaSpace.getVersion();

        final Map<String, String> labels = kibanaSpaceLabels(dataCenter, kibanaSpace.name());

        final V1ObjectMeta meta = dataCenterUpdateAction.builder.cloneV1ObjectMetaFromPodTemplate(kibanaSpace.getPodTemplate())
                .name(kibanaNameDc(dataCenter, kibanaSpace))
                .namespace(dataCenterMetadata.getNamespace());
        for(Map.Entry<String, String> entry : labels.entrySet())
            meta.putLabelsItem(entry.getKey(), entry.getValue());
        meta.putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
        meta.putAnnotationsItem(OperatorLabels.KIBANA_SPACE_FINGERPRINT, kibanaSpace.fingerprint(dataCenter.getSpec().getKibana().getImage()));

        final V1Container kibanaContainer = dataCenterUpdateAction.builder.cloneV1ContainerFromPodTemplate(kibanaSpace.getPodTemplate(), "kibana");
        if (kibanaContainer.getResources() == null) {
            // default kibana resources
            kibanaContainer.resources(new V1ResourceRequirements()
                    .putRequestsItem("cpu", Quantity.fromString("250m"))
                    .putRequestsItem( "memory", Quantity.fromString("1Gi"))
                    .putLimitsItem("cpu", Quantity.fromString("1000m"))
                    .putLimitsItem( "memory", Quantity.fromString("1Gi"))
            );
        }

        final V1PodSpec kibanaPodSpec = dataCenterUpdateAction.builder.clonePodSpecFromPodTemplate(kibanaSpace.getPodTemplate());
        final V1PodSpec elassandraPodSpec = dataCenterUpdateAction.builder.clonePodSpecFromPodTemplate(dataCenterSpec.getPodTemplate());

        // inherit service account
        if (kibanaPodSpec.getServiceAccountName() == null) {
            kibanaPodSpec.setServiceAccountName(elassandraPodSpec.getServiceAccount());
        }
        // inherit the priorityClassName of the Elassandra datacenter if not specified
        if (kibanaPodSpec.getPriorityClassName() == null) {
            kibanaPodSpec.setPriorityClassName(elassandraPodSpec.getPriorityClassName());
        }
        Map<String, V1Container> containerMap = kibanaPodSpec.getContainers().stream().collect(Collectors.toMap(V1Container::getName, Function.identity()));
        containerMap.put(kibanaContainer.getName(), kibanaContainer);
        kibanaPodSpec.setContainers(new ArrayList<>(containerMap.values()));

        final V1Deployment deployment = new V1Deployment()
                .metadata(meta)
                .spec(new V1DeploymentSpec()
                        // delay the creation of the reaper pod, after we have created the reaper_db keyspace
                        .replicas(kibanaReplicas(dataCenter, dataCenterStatus, kibanaSpace))
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(meta)
                                .spec(kibanaPodSpec)
                        )
                );

        kibanaContainer
                .name("kibana")
                .image(dataCenter.getSpec().getKibana().getImage())
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
                        .value(kibanaSpace.index(null))
                )
                .addEnvItem(new V1EnvVar().name("LOGGING_VERBOSE").value("true"))
        //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_ENABLED").value("false"))
        //.addEnvItem(new V1EnvVar().name("XPACK_SECURITY_ENABLED").value("false"))
        //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_UI_CONTAINER_ELASTICSEARCH_ENABLED").value("false"))
        ;

        // kibana with cassandra authentication
        if (!Objects.equals(dataCenterSpec.getCassandra().getAuthentication(), Authentication.NONE)) {
            String kibanaSecretName = kibanaName(dataCenter, kibanaSpace);
            kibanaContainer
                    .addEnvItem(new V1EnvVar()
                            .name("ELASTICSEARCH_USERNAME")
                            .value(kibanaSpace.role())
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
            kibanaPodSpec.addVolumesItem(new V1Volume()
                    .name("truststore")
                    .secret(new V1SecretVolumeSource()
                            .secretName(authorityManager.getPublicCaSecretName(dataCenterSpec.getClusterName()))
                    )
            );
            kibanaContainer
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
                    if (!Strings.isNullOrEmpty(kibanaSpace.getIngressSuffix())) {
                        String kibanaHost = kibanaSpace.name() + (kibanaSpace.name().length() > 0 ? "-" : "") + kibanaSpace.getIngressSuffix();
                        logger.info("Creating kibana ingress for host={}", kibanaHost);
                        final V1ObjectMeta ingressMeta = new V1ObjectMeta()
                                .name(kibanaNameDc(dataCenter, kibanaSpace))
                                .namespace(dataCenterMetadata.getNamespace())
                                .labels(labels)
                                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());
                        if (kibanaSpace.getIngressAnnotations() != null && !kibanaSpace.getIngressAnnotations().isEmpty()) {
                            kibanaSpace.getIngressAnnotations().entrySet().stream()
                                    .map(e -> ingressMeta.putAnnotationsItem(e.getKey(), e.getValue()));
                        }
                        final NetworkingV1beta1Ingress ingress = new NetworkingV1beta1Ingress()
                                .metadata(ingressMeta)
                                .spec(new NetworkingV1beta1IngressSpec()
                                        .addRulesItem(new NetworkingV1beta1IngressRule()
                                                .host(kibanaHost)
                                                .http(new NetworkingV1beta1HTTPIngressRuleValue()
                                                        .addPathsItem(new NetworkingV1beta1HTTPIngressPath()
                                                                .path("/")
                                                                .backend(new NetworkingV1beta1IngressBackend()
                                                                        .serviceName(meta.getName())
                                                                        .servicePort(new IntOrString(5601)))
                                                        ))
                                        )
                                        .addTlsItem(new NetworkingV1beta1IngressTLS()
                                                .addHostsItem(kibanaHost)
                                        )
                                );
                        return k8sResourceUtils.createOrReplaceNamespacedIngress(ingress).map(i -> s);
                    }
                    return Single.just(s);
                })
                .flatMap(s -> createKibanaSecretIfNotExists(dataCenter, kibanaSpace))
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
