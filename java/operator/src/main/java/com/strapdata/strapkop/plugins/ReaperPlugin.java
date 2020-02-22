package com.strapdata.strapkop.plugins;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.*;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.*;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.models.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.uri.UriTemplate;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Manage reaper deployment
 */
@Singleton
public class ReaperPlugin extends AbstractPlugin {

    private String reaperAdminPassword = null; // keep password to avoid secret reloading.

    public static final Map<String, String> PODS_SELECTOR = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator",
            "app", "reaper"
    );

    public static final String APP_SERVICE_NAME = "app";
    public static final String ADMIN_SERVICE_NAME = "admin";
    public static final int APP_SERVICE_PORT = 8080;      // the webui
    public static final int ADMIN_SERVICE_PORT = 8081;    // the REST API

    public ReaperPlugin(final ApplicationContext context,
                        K8sResourceUtils k8sResourceUtils,
                        AuthorityManager authorityManager,
                        CoreV1Api coreApi,
                        AppsV1Api appsApi,
                        OperatorConfig operatorConfig,
                        MeterRegistry meterRegistry) {
            super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
    }

    public static final CqlKeyspace REAPER_KEYSPACE = new CqlKeyspace("reaper_db", 3) {
        @Override
        public Single<CqlKeyspace> createIfNotExistsKeyspace(DataCenter dataCenter, CqlSessionSupplier sessionSupplier) throws Exception {
            return super.createIfNotExistsKeyspace(dataCenter, sessionSupplier).map(ks -> {
                dataCenter.getStatus().setReaperPhase(ReaperPhase.KEYSPACE_CREATED);
                return ks;
            });
        }
    };

    @Override
    public void syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        if (dataCenter.getSpec().getReaper().getEnabled()) {
            cqlKeyspaceManager.addIfAbsent(dataCenter, REAPER_KEYSPACE.getName(), () -> REAPER_KEYSPACE);
        } else {
            cqlKeyspaceManager.remove(dataCenter, REAPER_KEYSPACE.getName());
        }
    }

    public static final CqlRole REAPER_ROLE = new CqlRole()
            .withUsername("reaper")
            .withSecretKey(DataCenterUpdateAction.KEY_REAPER_PASSWORD)
            .withSuperUser(false)
            .withLogin(true)
            .withApplied(false)
            .withGrantStatements(ImmutableList.of("GRANT ALL PERMISSIONS ON KEYSPACE reaper_db TO reaper"))
            .withPostCreateHandler(ReaperPlugin::postCreateReaper);

    public static void postCreateReaper(DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) throws Exception {
        dataCenter.getStatus().setReaperPhase(ReaperPhase.ROLE_CREATED);
        logger.debug("reaper role created for dc={}, ReaperStatus=ROLE_CREATED", dataCenter.getMetadata().getName());
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getReaper().getEnabled();
    }

    @Override
    public void syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {
        cqlRoleManager.addIfAbsent(dataCenter, REAPER_ROLE.getUsername(), () -> REAPER_ROLE.duplicate());
    }

    public static String reaperName(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-reaper", dataCenter);
    }


    public static Map<String, String> reaperLabels(DataCenter dataCenter) {
        final Map<String, String> labels = new HashMap<>(OperatorLabels.datacenter(dataCenter));
        labels.put("app", "reaper"); // overwrite label app
        return labels;
    }

    @Override
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        if (DataCenterPhase.RUNNING.equals(dataCenter.getStatus().getPhase())) {
            // reconcile reaper pds only of the DC is in running state in order to avoid connection issue on Reaper startup
            return (dataCenter.getSpec().getReaper().getEnabled()) ? createOrReplaceReaperObjects(dataCenter) : delete(dataCenter);
        } else {
            logger.debug("DataCenter {} isn't in RUNNING Phase, skip reaper reconciliation");
            return Completable.complete();
        }
    }

    @Override
    public Completable delete(final DataCenter dataCenter) throws ApiException {
        final String reaperLabelSelector = OperatorLabels.toSelector(reaperLabels(dataCenter));
        return Completable.mergeArray(new Completable[] {
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector)
        }).andThen(Completable.fromAction(() -> {
            DataCenterStatus status = dataCenter.getStatus();
            status.setReaperPhase(ReaperPhase.ROLE_CREATED); // step back the phase to be in consistent state next time Reaper will be enabled
            REAPER_ROLE.setApplied(false); // mark Role as not applied to create it if the DC is recreated
            this.reaperAdminPassword = null;
        }));
    }

    /**
     * @return The number of reaper pods depending on ReaperStatus
     */
    private int reaperReplicas(final DataCenter dataCenter) {
        switch(dataCenter.getStatus().getReaperPhase()) {
            case NONE:
                return 0;
            case KEYSPACE_CREATED:
                return (dataCenter.getSpec().getAuthentication().equals(Authentication.NONE)) ? 1 : 0;
            case ROLE_CREATED:
            case REGISTERED:
            default:
                return 1;
        }
    }


    public Completable createOrReplaceReaperObjects(final DataCenter dataCenter) throws ApiException, StrapkopException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final DataCenterStatus dataCenterStatus = dataCenter.getStatus();

        final Map<String, String> labels = reaperLabels(dataCenter);

        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(reaperName(dataCenter))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());

        final V1Container container = new V1Container();

        // Create an accumulator for JAVA_OPTS
        // TODO do we have to make HEAP values configurable in the reaper section of DCSpec ??
        StringBuilder javaOptsBuilder = new StringBuilder(200);
        if (dataCenterSpec.getJmxmpEnabled()) {
            javaOptsBuilder.append(" -Ddw.jmxmp.enabled=true ");
            if (dataCenterSpec.getSsl() && (!dataCenterSpec.getJmxmpEnabled() || (dataCenterSpec.getJmxmpEnabled() && dataCenterSpec.getJmxmpOverSSL()))) {
                javaOptsBuilder.append(" -Ddw.jmxmp.ssl=true ");
            }
        }

        final V1PodSpec podSpec = new V1PodSpec()
                .serviceAccountName(dataCenterSpec.getAppServiceAccount())
                .addContainersItem(container);

        if (dataCenterSpec.getImagePullSecrets() != null) {
            for(String secretName : dataCenterSpec.getImagePullSecrets()) {
                final V1LocalObjectReference pullSecret = new V1LocalObjectReference().name(secretName);
                podSpec.addImagePullSecretsItem(pullSecret);
            }
        }

        final V1Deployment deployment = new V1Deployment()
                .metadata(meta)
                .spec(new V1DeploymentSpec()
                        // delay the creation of the reaper pod, after we have created the reaper_db keyspace
                        .replicas(reaperReplicas(dataCenter))
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(labels))
                                .spec(podSpec)
                        )
                );

        // common configuration
        container
                .name("reaper")
                .image(dataCenterSpec.getReaper().getImage())
                .terminationMessagePolicy("FallbackToLogsOnError")
                .addPortsItem(new V1ContainerPort()
                        .name(APP_SERVICE_NAME)
                        .containerPort(APP_SERVICE_PORT)
                        .protocol("TCP")
                )
                .addPortsItem(new V1ContainerPort()
                        .name(ADMIN_SERVICE_NAME)
                        .containerPort(ADMIN_SERVICE_PORT)
                        .protocol("TCP")
                )
                .livenessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction()
                                .path("/")
                                .port(new IntOrString(ADMIN_SERVICE_PORT))
                        )
                        .initialDelaySeconds(60)
                        .periodSeconds(20)
                        .timeoutSeconds(5)
                )
                .readinessProbe(new V1Probe()
                        .httpGet(new V1HTTPGetAction()
                                .path("/")
                                .port(new IntOrString(ADMIN_SERVICE_PORT))
                        )
                        .initialDelaySeconds(10)
                        .periodSeconds(10)
                        .timeoutSeconds(5)
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_DATACENTER_AVAILABILITY")
                        .value("EACH")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_AUTO_SCHEDULING_ENABLED")
                        .value("true")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_JMX_AUTH_PASSWORD")
                        .valueFrom(new V1EnvVarSource()
                                .secretKeyRef(new V1SecretKeySelector()
                                        .name(OperatorNames.clusterSecret(dataCenter))
                                        .key(DataCenterUpdateAction.KEY_JMX_PASSWORD)
                                )
                        )
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_JMX_AUTH_USERNAME")
                        .value("cassandra")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_AUTH_USER")
                        .value("admin")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_AUTH_PASSWORD")
                        .valueFrom(new V1EnvVarSource()
                                .secretKeyRef(new V1SecretKeySelector()
                                        .name(reaperSecretName(dataCenter))
                                        .key("reaper.admin_password")
                                )
                        )
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_STORAGE_TYPE")
                        .value("cassandra")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_CASS_CLUSTER_NAME")
                        .value(dataCenterSpec.getClusterName())
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_CASS_CONTACT_POINTS")
                        .value("[" + OperatorNames.seedsService(dataCenter) + "]")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_CASS_PORT")
                        .value(dataCenterSpec.getNativePort().toString())
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_CASS_KEYSPACE")
                        .value("reaper_db")
                )
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_CASS_LOCAL_DC")
                        .value(dataCenterSpec.getDatacenterName())
                )
                .addEnvItem(new V1EnvVar()
                        .name("JWT_SECRET")
                        .value(Base64.getEncoder().encodeToString(dataCenterSpec.getReaper().getJwtSecret().getBytes()))
                );


        // reaper with cassandra authentication
        if (!Objects.equals(dataCenterSpec.getAuthentication(), Authentication.NONE)) {
            container
                    .addEnvItem(new V1EnvVar()
                            .name("REAPER_CASS_AUTH_ENABLED")
                            .value("true")
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("REAPER_CASS_AUTH_USERNAME")
                            .value("reaper") // TODO: create an account for reaper
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("REAPER_CASS_AUTH_PASSWORD")
                            .valueFrom(new V1EnvVarSource()
                                    .secretKeyRef(new V1SecretKeySelector()
                                            .name(OperatorNames.clusterSecret(dataCenter))
                                            .key(DataCenterUpdateAction.KEY_REAPER_PASSWORD)
                                    )
                            )
                    );
        } else {
            container.addEnvItem(new V1EnvVar()
                    .name("REAPER_CASS_AUTH_ENABLED")
                    .value("false")
            );
        }

        // reaper with cassandra ssl on native port
        if (Boolean.TRUE.equals(dataCenterSpec.getSsl())) {
            podSpec.addVolumesItem(new V1Volume()
                    .name("truststore")
                    .secret(new V1SecretVolumeSource()
                            .secretName(authorityManager.getPublicCaSecretName())
                            .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_CACERT_PEM).path(AuthorityManager.SECRET_CACERT_PEM))
                            .addItemsItem(new V1KeyToPath().key(AuthorityManager.SECRET_TRUSTSTORE_P12).path(AuthorityManager.SECRET_TRUSTSTORE_P12))
                    )
            );

            container
                    .addEnvItem(new V1EnvVar()
                            .name("REAPER_CASS_NATIVE_PROTOCOL_SSL_ENCRYPTION_ENABLED")
                            .value("true")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .mountPath("/truststore")
                            .name("truststore")
                    );
            // accumulate truststore options into JAVA_OPTS builder
            javaOptsBuilder.append(" -Dssl.enable=true -Djavax.net.ssl.trustStore=/truststore/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit ");

        } else {
            container.addEnvItem(new V1EnvVar()
                    .name("REAPER_CASS_NATIVE_PROTOCOL_SSL_ENCRYPTION_ENABLED")
                    .value("false")
            );
        }

        container.addEnvItem(new V1EnvVar()
                .name("JAVA_OPTS")
                .value(javaOptsBuilder.toString()));


        // create reaper service
        final V1Service service = new V1Service()
                .metadata(meta)
                .spec(new V1ServiceSpec()
                        .type("ClusterIP")
                        .addPortsItem(new V1ServicePort().name(APP_SERVICE_NAME).port(APP_SERVICE_PORT))
                        .addPortsItem(new V1ServicePort().name(ADMIN_SERVICE_NAME).port(ADMIN_SERVICE_PORT))
                        .selector(labels)
                );

        // create reaper ingress
        String ingressSuffix = System.getenv("INGRESS_SUFFIX");
        final V1beta1Ingress ingress;
        if (!Strings.isNullOrEmpty(ingressSuffix)) {
            String baseHostname = UriTemplate.of(ingressSuffix).expand(ImmutableMap.of(
                    "namespace", dataCenterMetadata.getNamespace(),
                    "datacenterName", dataCenterSpec.getDatacenterName().toLowerCase(Locale.ROOT),
                    "clusterName", dataCenterSpec.getClusterName().toLowerCase(Locale.ROOT)));
            String reaperAppHost = "reaper-" + baseHostname;
            String reaperAdminHost = "admin-reaper-" + baseHostname;
            logger.info("Creating reaper ingress for reaperAppHost={} reaperAdminHost={}", reaperAppHost, reaperAdminHost);
            ingress = new V1beta1Ingress()
                    .metadata(meta)
                    .spec(new V1beta1IngressSpec()
                            .addRulesItem(new V1beta1IngressRule()
                                    .host(reaperAppHost)
                                    .http(new V1beta1HTTPIngressRuleValue()
                                            .addPathsItem(new V1beta1HTTPIngressPath()
                                                    .path("/")
                                                    .backend(new V1beta1IngressBackend()
                                                            .serviceName(reaperName(dataCenter))
                                                            .servicePort(new IntOrString(APP_SERVICE_PORT)))
                                            )
                                    )
                            )
                            .addTlsItem(new V1beta1IngressTLS().addHostsItem(reaperAppHost))
                            .addRulesItem(new V1beta1IngressRule()
                                    .host(reaperAdminHost)
                                    .http(new V1beta1HTTPIngressRuleValue()
                                            .addPathsItem(new V1beta1HTTPIngressPath()
                                                    .path("/")
                                                    .backend(new V1beta1IngressBackend()
                                                            .serviceName(reaperName(dataCenter))
                                                            .servicePort(new IntOrString(ADMIN_SERVICE_PORT)))
                                            )
                                    )
                            )
                            .addTlsItem(new V1beta1IngressTLS().addHostsItem(reaperAdminHost))

                    );
        } else {
            ingress = null;
        }

        // abort deployment replacement if it is already up to date (according to the annotation datacenter-generation and to spec.replicas)
        // this is important because otherwise it generate a "larsen" : deployment replace -> k8s event -> reconciliation -> deployment replace...
        try {
            final V1Deployment existingDeployment = appsApi.readNamespacedDeployment(meta.getName(), meta.getNamespace(), null, null, null);
            final String datacenterGeneration = existingDeployment.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION);

            if (datacenterGeneration == null) {
                throw new StrapkopException(String.format("reaper deployment %s miss the annotation datacenter-generation", meta.getName()));
            }

            if (Objects.equals(Long.parseLong(datacenterGeneration), dataCenterMetadata.getGeneration()) &&
                    Objects.equals(existingDeployment.getSpec().getReplicas(), deployment.getSpec().getReplicas())) {
                if (dataCenter.getStatus().getReaperPhase().equals(ReaperPhase.REGISTERED)) {
                    return Completable.complete();
                } else {
                    return Completable.fromCallable(new Callable<DataCenter>() {
                        /**
                         * Computes a result, or throws an exception if unable to do so.
                         *
                         * @return computed result
                         * @throws Exception if unable to compute a result
                         */
                        @Override
                        public DataCenter call() throws Exception {
                            return reconcileReaperRegistration(dataCenter);
                        }
                    });
                }
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        return getOrCreateReaperAdminPassword(dataCenter)
                .flatMap(s -> k8sResourceUtils.createOrReplaceNamespacedService(service))
                .flatMap(s -> ingress == null ? Single.just(s) : k8sResourceUtils.createOrReplaceNamespacedIngress(ingress).map(i -> s))
                .flatMap(s -> k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment))
                .flatMapCompletable(d -> Completable.fromCallable(new Callable<DataCenter>() {
                    /**
                     * Computes a result, or throws an exception if unable to do so.
                     *
                     * @return computed result
                     * @throws Exception if unable to compute a result
                     */
                    @Override
                    public DataCenter call() throws Exception {
                        return reconcileReaperRegistration(dataCenter);
                    }
                }));
    }

    private String reaperSecretName(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-reaper", dataCenter);
    }


    private Single<V1Secret> getOrCreateReaperAdminPassword(DataCenter dataCenter) throws ApiException {
        V1ObjectMeta secretMeta = new V1ObjectMeta()
                .name(reaperSecretName(dataCenter))
                .namespace(dataCenter.getMetadata().getNamespace())
                .addOwnerReferencesItem(OperatorNames.ownerReference(dataCenter))
                .labels(OperatorLabels.cluster(dataCenter.getSpec().getClusterName()));

        return k8sResourceUtils.readOrCreateNamespacedSecret(secretMeta, () -> {
            V1Secret secret = new V1Secret().metadata(secretMeta);
            secret.putStringDataItem("reaper.admin_password", UUID.randomUUID().toString());
            return secret;
        });
    }

    /**
     * As soon as reaper_db keyspace is created, this function try to ping the reaper api and, if success, register the datacenter.
     * THe registration is done only once. If the datacenter is unregistered by the user, it will not register it again automatically.
     */
    private DataCenter reconcileReaperRegistration(DataCenter dc) throws StrapkopException, ApiException {

        if (!ReaperPhase.REGISTERED.equals(dc.getStatus().getReaperPhase()) && (
                (dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperPhase.KEYSPACE_CREATED.equals(dc.getStatus().getReaperPhase())) ||
                        !dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperPhase.ROLE_CREATED.equals(dc.getStatus().getReaperPhase())
        )) {

            if (reaperAdminPassword == null)
                reaperAdminPassword = loadReaperAdminPassword(dc);

            try (ReaperClient reaperClient = new ReaperClient(dc, "admin", reaperAdminPassword)) {

                if (!reaperClient.ping().blockingGet()) {
                    logger.info("reaper is not ready before registration, waiting");
                }
                else {
                    reaperClient.registerCluster()
                            .observeOn(Schedulers.io())
                            .subscribeOn(Schedulers.io())
                            .blockingGet();
                    dc.getStatus().setReaperPhase(ReaperPhase.REGISTERED);
                    logger.info("registered dc={} in cassandra-reaper", dc.getMetadata().getName());
                }
            }
            catch (Exception e) {
                dc.getStatus().setLastMessage(e.getMessage());
                logger.error("error while registering dc={} in cassandra-reaper", dc.getMetadata().getName(), e);
            }
        }
        return dc;
    }

    // TODO: cache cluster secret to avoid loading secret again and again
    private String loadReaperAdminPassword(DataCenter dc) throws ApiException, StrapkopException {
        final String secretName = reaperSecretName(dc);
        final V1Secret secret = coreApi.readNamespacedSecret(secretName,
                dc.getMetadata().getNamespace(),
                null,
                null,
                null);
        final byte[] password = secret.getData().get("reaper.admin_password");
        if (password == null) {
            throw new StrapkopException(String.format("secret %s does not contain reaper.admin_password", secretName));
        }
        return new String(password);
    }

}
