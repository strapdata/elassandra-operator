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
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.*;
import io.reactivex.schedulers.Schedulers;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Manage reaper deployment
 */
@Singleton
public class ReaperPlugin extends AbstractPlugin {

    public static final Map<String, String> PODS_SELECTOR = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator",
            "app", "reaper"
    );

    public static final String APP_SERVICE_NAME = "app";
    public static final String ADMIN_SERVICE_NAME = "admin";
    public static final int APP_SERVICE_PORT = 8080;      // the webui
    public static final int ADMIN_SERVICE_PORT = 8081;    // the REST API

    public final Scheduler registrationScheduler;

    public ReaperPlugin(final ApplicationContext context,
                        K8sResourceUtils k8sResourceUtils,
                        AuthorityManager authorityManager,
                        CoreV1Api coreApi,
                        AppsV1Api appsApi,
                        OperatorConfig operatorConfig,
                        MeterRegistry meterRegistry,
                        ExecutorFactory executorFactory,
                        @Named("reaper") UserExecutorConfiguration userExecutorConfiguration) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
        this.registrationScheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
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
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException, IOException {
        if (DataCenterPhase.RUNNING.equals(dataCenter.getStatus().getPhase())) {
            // reconcile reaper pds only of the DC is in running state in order to avoid connection issue on Reaper startup
            return (dataCenter.getSpec().getReaper().getEnabled()) ? createOrReplaceReaperObjects(dataCenter) : delete(dataCenter);
        } else {
            logger.debug("datacenter={} isn't in RUNNING Phase, skip reaper reconciliation", dataCenter.id());
            return Completable.complete();
        }
    }

    @Override
    public Completable delete(final DataCenter dataCenter) throws ApiException {
        final String reaperLabelSelector = OperatorLabels.toSelector(reaperLabels(dataCenter));
        return Completable.mergeArray(new Completable[]{
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector)
        }).andThen(Completable.fromAction(() -> {
            DataCenterStatus status = dataCenter.getStatus();
            status.setReaperPhase(ReaperPhase.ROLE_CREATED); // step back the phase to be in consistent state next time Reaper will be enabled
            REAPER_ROLE.setApplied(false); // mark Role as not applied to create it if the DC is recreated
        }));
    }

    /**
     * @return The number of reaper pods depending on ReaperStatus
     */
    private int reaperReplicas(final DataCenter dataCenter) {
        switch (dataCenter.getStatus().getReaperPhase()) {
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


    public Completable createOrReplaceReaperObjects(final DataCenter dataCenter) throws ApiException, StrapkopException, IOException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final DataCenterStatus dataCenterStatus = dataCenter.getStatus();

        final Map<String, String> labels = reaperLabels(dataCenter);

        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(reaperName(dataCenter))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, dataCenter.getMetadata().getGeneration().toString());

        // abort deployment replacement if it is already up to date (according to the annotation datacenter-generation and to spec.replicas)
        // this is important because otherwise it generate a "larsen" : deployment replace -> k8s event -> reconciliation -> deployment replace...
        Boolean deployRepear = true;
        try {
            final V1Deployment existingDeployment = appsApi.readNamespacedDeployment(meta.getName(), meta.getNamespace(), null, null, null);
            final String reaperDatacenterGeneration = existingDeployment.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION);

            if (reaperDatacenterGeneration == null) {
                throw new StrapkopException(String.format("reaper deployment %s miss the annotation datacenter-generation", meta.getName()));
            }

            if (reaperDatacenterGeneration.equals(dataCenter.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION))) {
                deployRepear = false;
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        // no need to update repear deployment, already deployed with the current datacenter-generation annotation
        if (!deployRepear)
            return Completable.complete();


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
            for (String secretName : dataCenterSpec.getImagePullSecrets()) {
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
                        .value("[" + OperatorNames.nodesService(dataCenter) + "]")
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
        final V1beta1Ingress ingress;
        if (!Strings.isNullOrEmpty(dataCenterSpec.getReaper().getIngressSuffix())) {
            String baseHostname = dataCenterSpec.getReaper().getIngressSuffix();
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

        // deploy manifests in parallel
        List<CompletableSource> todoList = new ArrayList<>();
        todoList.add(k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment).ignoreElement());
        todoList.add(k8sResourceUtils.createOrReplaceNamespacedService(service).ignoreElement());
        if (ingress != null)
            todoList.add(k8sResourceUtils.createOrReplaceNamespacedIngress(ingress).ignoreElement());

        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]));
    }

    private String reaperSecretName(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-reaper", dataCenter);
    }

    /**
     * Called form the ReaperPodHandler when repaer pod is ready.
     * As soon as reaper_db keyspace is created, this function try to ping the reaper api and, if success, register the datacenter.
     * THe registration is done only once. If the datacenter is unregistered by the user, it will not register it again automatically.
     */
    public Completable registerIfNeeded(DataCenter dc) throws StrapkopException, ApiException, MalformedURLException {
        if (!ReaperPhase.REGISTERED.equals(dc.getStatus().getReaperPhase()) && (
                (dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperPhase.KEYSPACE_CREATED.equals(dc.getStatus().getReaperPhase())) ||
                        !dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperPhase.ROLE_CREATED.equals(dc.getStatus().getReaperPhase())
        )) {
            ReaperClient reaperClient = new ReaperClient(dc, this.registrationScheduler);
            return loadReaperAdminPassword(dc)
                    .observeOn(Schedulers.io())
                    .subscribeOn(Schedulers.io())
                    .flatMap(password ->
                            reaperClient.registerCluster("admin", password)
                            .retryWhen((Flowable<Throwable> f) -> f.take(9).delay(21, TimeUnit.SECONDS))
                    )
                    .flatMapCompletable(bool -> {
                        if (bool) {
                            dc.getStatus().setReaperPhase(ReaperPhase.REGISTERED);
                            logger.info("dc={} cassandra-reaper registred ", dc.id());
                            return registerScheduledRepair(dc);
                        }
                        return Completable.complete();
                    })
                    .doFinally(() -> {
                        if (reaperClient != null) {
                            try {
                                reaperClient.close();
                            } catch (Throwable t) {
                            }
                        }
                    })
                    .doOnError(e -> {
                        dc.getStatus().setLastMessage(e.getMessage());
                        logger.error("datacenter={} error while registering in cassandra-reaper", dc.id(), e);
                    });
        }
        return Completable.complete();
    }

    // TODO: cache cluster secret to avoid loading secret again and again
    private Single<String> loadReaperAdminPassword(DataCenter dc) throws ApiException, StrapkopException {
        final String secretName = reaperSecretName(dc);
        return k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), secretName)
                .map(secret -> {
                    final byte[] password = secret.getData().get("reaper.admin_password");
                    if (password == null) {
                        throw new StrapkopException(String.format("secret %s does not contain reaper.admin_password", secretName));
                    }
                    return new String(password);
                });
    }

    public Completable registerScheduledRepair(DataCenter dc) throws MalformedURLException, ApiException {
        ReaperClient reaperClient = new ReaperClient(dc, this.registrationScheduler);
        return loadReaperAdminPassword(dc)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .flatMapCompletable(password -> {
                    List<CompletableSource> todoList = new ArrayList<>();
                    for(ReaperScheduledRepair reaperScheduledRepair : dc.getSpec().getReaper().getReaperScheduledRepairs()) {
                        todoList.add(reaperClient.registerScheduledRepair("admin", password, reaperScheduledRepair));
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]));
                })
                .doFinally(() -> {
                    if (reaperClient != null) {
                        try {
                            reaperClient.close();
                        } catch (Throwable t) {
                        }
                    }
                })
                .doOnError(e -> {
                    dc.getStatus().setLastMessage(e.getMessage());
                    logger.error("datacenter={} error while registering scheduled repair", dc.id(), e);
                });
    }
}
