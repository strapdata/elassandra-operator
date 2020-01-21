package com.strapdata.strapkop.plugins;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.strapdata.dns.DnsConfiguration;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.models.*;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
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
                        DnsConfiguration dnsConfiguration) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, dnsConfiguration);
    }

    @Override
    public void syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        for(KibanaSpace kibana : dataCenter.getSpec().getKibana().getSpaces()) {
            cqlKeyspaceManager.addIfAbsent(dataCenter, kibana.keyspace(), () -> new CqlKeyspace()
                    .withName(kibana.keyspace())
                    .withRf(3)
            );
        }
    }

    @Override
    public void syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {
        for(KibanaSpace kibana : dataCenter.getSpec().getKibana().getSpaces()) {
            try {
                createKibanaSecretIfNotExists(dataCenter, kibana);
                cqlRoleManager.addIfAbsent(dataCenter, kibana.keyspace(), () -> new CqlRole()
                        .withUsername(kibana.role())
                        .withSecretKey("kibana.kibana_password")
                        .withSecretNameProvider( dc -> OperatorNames.clusterChildObjectName("%s-"+kibana.role(), dc))
                        .withApplied(false)
                        .withSuperUser(true)
                        .withGrantStatements(
                                ImmutableList.of(
                                        String.format(Locale.ROOT,"GRANT ALL PERMISSIONS ON KEYSPACE \"%s\" TO %s", kibana.keyspace(), kibana.role()),
                                        String.format(Locale.ROOT,"INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','cluster:monitor/.*','.*')", kibana.index()),
                                        String.format(Locale.ROOT,"INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','indices:.*','.*')", kibana.index())
                                )
                        )
                );
            } catch (ApiException e) {
                logger.error("Failed to find or create kibana secret", e);
            }
        }
    }

    public static String kibanaName(DataCenter dataCenter, KibanaSpace space) {
        return "elassandra-" + dataCenter.getSpec().getClusterName() + "-" + space.name();
    }

    public static String kibanaNameDc(DataCenter dataCenter, KibanaSpace space) {
        return "elassandra-" + dataCenter.getSpec().getClusterName() + "-" + dataCenter.getSpec().getDatacenterName() + "-kibana" + (space.name().length() > 0 ? "-" : "") + space.name();
    }


    public static Map<String, String> kibanaLabels(DataCenter dataCenter, KibanaSpace space) {
        final Map<String, String> labels = new HashMap<>(OperatorLabels.datacenter(dataCenter));
        labels.put("app", space.name()); // overwrite label app
        return labels;
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getElasticsearchEnabled();
    }

    @Override
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
            // remove deleted kibana spaces
            Set<String> deployedKibanaSpaces = dataCenter.getStatus().getKibanaSpaces();
            Map<String, KibanaSpace> kibanaMap = dataCenter.getSpec().getKibana().getSpaces().stream().collect(Collectors.toMap(KibanaSpace::getName, Function.identity()));
            Completable deleteCompletable = io.reactivex.Observable.fromIterable(Sets.difference(deployedKibanaSpaces, dataCenter.getSpec().getKibana().getSpaces().stream().map(KibanaSpace::getName).collect(Collectors.toSet())))
                    .flatMapCompletable(spaceToDelete -> {
                        logger.debug("Deleting kibana space={}", spaceToDelete);
                        dataCenter.getStatus().getKibanaSpaces().remove(spaceToDelete);
                        return delete(dataCenter, kibanaMap.get(spaceToDelete));
                    });


            Completable createCompletable = io.reactivex.Observable.fromIterable(dataCenter.getSpec().getKibana().getSpaces())
                    .flatMapCompletable(kibanaSpace -> {
                        dataCenter.getStatus().getKibanaSpaces().add(kibanaSpace.getName());
                        return createOrReplaceReaperObjects(dataCenter, kibanaSpace);
                    });

            return deleteCompletable.andThen(createCompletable);
    }

    @Override
    public Completable delete(final DataCenter dataCenter) throws ApiException {
        return Completable.mergeArray(dataCenter.getSpec().getKibana().getSpaces().stream()
                .map(kibanaSpace -> delete(dataCenter, kibanaSpace))
                .toArray(Completable[]::new));
    }

    public Completable delete(final DataCenter dataCenter, KibanaSpace space) {
        final String kibanaLabelSelector = OperatorLabels.toSelector(kibanaLabels(dataCenter, space));
        return Completable.mergeArray(new Completable[]{
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, kibanaLabelSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, kibanaLabelSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, kibanaLabelSelector)
        });
    }


    /**
     * @return The number of reaper pods depending on ReaperStatus
     */
    private int kibanaReplicas(final DataCenter dataCenter, KibanaSpace kibanaSpace) {
        return (dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().contains(kibanaSpace.keyspace())) ? 1 : 0;
    }


    public Completable createOrReplaceReaperObjects(final DataCenter dataCenter, KibanaSpace space) throws ApiException, StrapkopException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final DataCenterStatus dataCenterStatus = dataCenter.getStatus();

        final Map<String, String> labels = kibanaLabels(dataCenter, space);

        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(kibanaNameDc(dataCenter, space))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());

        final V1Container container = new V1Container();

        final V1PodSpec podSpec = new V1PodSpec()
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

        if (dataCenterSpec.getImagePullSecrets() != null) {
            for(String secretName : dataCenterSpec.getImagePullSecrets()) {
                final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secretName);
                podSpec.addImagePullSecretsItem(pullSecret);
            }
        }

        container
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
                        .value( (Boolean.TRUE.equals(dataCenterSpec.getEnterprise().getHttps()) ? "https://" : "http://") +
                                OperatorNames.elasticsearchService(dataCenter) + "." + dataCenterMetadata.getNamespace() + ".svc.cluster.local:9200")
                )
                .addEnvItem(new V1EnvVar()
                        .name("KIBANA_INDEX")
                        .value(space.index())
                )
                .addEnvItem(new V1EnvVar().name("LOGGING_VERBOSE").value("true"))
                //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_ENABLED").value("false"))
                //.addEnvItem(new V1EnvVar().name("XPACK_SECURITY_ENABLED").value("false"))
                //.addEnvItem(new V1EnvVar().name("XPACK_MONITORING_UI_CONTAINER_ELASTICSEARCH_ENABLED").value("false"))
        ;


        // kibana with cassandra authentication
        if (!Objects.equals(dataCenterSpec.getAuthentication(), Authentication.NONE)) {
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
        if (Boolean.TRUE.equals(dataCenterSpec.getSsl())) {
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
                    String ingressDomain = System.getenv("INGRESS_DOMAIN");
                    if (!Strings.isNullOrEmpty(ingressDomain)) {
                        String kibanaHost = space.name() + "-" + dataCenterSpec.getClusterName() + "-" + dataCenterSpec.getDatacenterName() + "-" + ingressDomain.replace("${namespace}", dataCenterMetadata.getNamespace());
                        logger.info("Creating kibana ingress for host={}", kibanaHost);
                        final V1beta1Ingress ingress = new V1beta1Ingress()
                                .metadata(meta)
                                .spec(new V1beta1IngressSpec()
                                        .addRulesItem(new V1beta1IngressRule()
                                                .host(kibanaHost)
                                                .http(new V1beta1HTTPIngressRuleValue()
                                                        .addPathsItem(new V1beta1HTTPIngressPath()
                                                                .path("/")
                                                                .backend(new V1beta1IngressBackend()
                                                                        .serviceName(meta.getName())
                                                                        .servicePort(new IntOrString(5601)))
                                                        )
                                                )
                                        )
                                        .addTlsItem(new V1beta1IngressTLS()
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
