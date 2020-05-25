package com.strapdata.strapkop.plugins;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.*;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
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
            OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR,
            OperatorLabels.APP, "reaper"
    );

    public static final String APP_SERVICE_NAME = "app";
    public static final String ADMIN_SERVICE_NAME = "admin";
    public static final int APP_SERVICE_PORT = 8080;      // the webui
    public static final int ADMIN_SERVICE_PORT = 8081;    // the REST API

    public static final String REAPER_KEYSPACE_NAME = "reaper_db";
    public static final String REAPER_ADMIN_PASSWORD_KEY = "reaper.admin_password";

    public final Scheduler registrationScheduler;

    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public ReaperPlugin(final ApplicationContext context,
                        K8sResourceUtils k8sResourceUtils,
                        AuthorityManager authorityManager,
                        CoreV1Api coreApi,
                        AppsV1Api appsApi,
                        OperatorConfig operatorConfig,
                        MeterRegistry meterRegistry,
                        CqlRoleManager cqlRoleManager,
                        CqlKeyspaceManager cqlKeyspaceManager,
                        ExecutorFactory executorFactory,
                        @Named("reaper") UserExecutorConfiguration userExecutorConfiguration) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
        this.registrationScheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    public static final CqlKeyspace REAPER_KEYSPACE = new CqlKeyspace().withName(REAPER_KEYSPACE_NAME).withRf(3).withRepair(true);

    @Override
    public Map<String, String> deploymentLabelSelector(DataCenter dc) {
        return reaperLabels(dc);
    }

    @Override
    public Completable syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        if (dataCenter.getSpec().getReaper().getEnabled()) {
            cqlKeyspaceManager.addIfAbsent(dataCenter, REAPER_KEYSPACE.getName(), () -> REAPER_KEYSPACE);
        } else {
            cqlKeyspaceManager.remove(dataCenter, REAPER_KEYSPACE.getName());
        }
        return Completable.complete();
    }

    /**
     * Cassandra reaper CQL role with all permission on the reaper keyspace.
     */
    public static final CqlRole REAPER_ROLE = new CqlRole()
            .withUsername("reaper")
            .withSecretKey(DataCenterUpdateAction.KEY_REAPER_PASSWORD)
            .withSuperUser(false)
            .withLogin(true)
            .withReconcilied(false)
            .withGrantStatements(ImmutableList.of(String.format(Locale.ROOT, "GRANT ALL PERMISSIONS ON KEYSPACE %s TO reaper", REAPER_KEYSPACE_NAME)));

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getReaper().getEnabled();
    }

    @Override
    public Completable syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {
        cqlRoleManager.addIfAbsent(dataCenter, REAPER_ROLE.getUsername(), () -> REAPER_ROLE.duplicate());
        return Completable.complete();
    }

    public static String reaperName(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-reaper", dataCenter);
    }


    public static Map<String, String> reaperLabels(DataCenter dataCenter) {
        return ImmutableMap.of(
                OperatorLabels.APP, "reaper",
                OperatorLabels.MANAGED_BY, OperatorLabels.ELASSANDRA_OPERATOR,
                OperatorLabels.PARENT, dataCenter.getMetadata().getName()
        );
    }

    /**
     * Return true if datacenter.status is updated
     *
     * @param dataCenter
     * @return
     * @throws ApiException
     * @throws StrapkopException
     * @throws IOException
     */
    @Override
    public Single<Boolean> reconcile(DataCenter dataCenter) throws StrapkopException {
        logger.trace("datacenter={} reaper.spec={}", dataCenter.id(), dataCenter.getSpec().getReaper());

        boolean reaperEnabled = dataCenter.getSpec().getReaper() != null && dataCenter.getSpec().getReaper().getEnabled();
        ReaperPhase reaperPhase = (dataCenter.getStatus().getReaperPhase() == null) ? ReaperPhase.NONE : dataCenter.getStatus().getReaperPhase();

        return this.listDeployments(dataCenter)
                .flatMap(deployments -> {
                    if ((!reaperEnabled || dataCenter.getSpec().isParked()) && !deployments.isEmpty()) {
                        return delete(dataCenter).map(b -> {
                            dataCenter.getStatus().setReaperPhase(ReaperPhase.NONE);
                            return true;
                        });
                    }

                    switch (reaperPhase) {
                        case NONE:
                            CqlRole reaperRole = cqlRoleManager.get(dataCenter, REAPER_ROLE.getUsername());
                            if (reaperRole != null && reaperRole.isReconcilied()) {
                                return createOrReplaceReaperObjects(dataCenter).map(b -> {
                                    dataCenter.getStatus().setReaperPhase(ReaperPhase.DEPLOYED);
                                    return true;
                                });
                            }
                            break;
                        case DEPLOYED:
                            break;

                        case RUNNING:
                            return register(dataCenter).toSingleDefault(true);

                        case REGISTERED:
                            // TODO: schedule/unschedule repairs
                            break;
                    }
                    return Single.just(false);
                });
    }

    @Override
    public Single<Boolean> delete(final DataCenter dataCenter) {
        final String reaperLabelSelector = OperatorLabels.toSelector(reaperLabels(dataCenter));
        return Completable.mergeArray(new Completable[]{
                k8sResourceUtils.deleteDeployment(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector),
                k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector)
        })
                .toSingleDefault(true)
                .map(b -> {
                    logger.debug("dc={} reaper undeployed", dataCenter.id());
                    return b;
                });
    }

    /**
     * @return The number of reaper pods depending on ReaperStatus
     */
    private int reaperReplicas(final DataCenter dataCenter) {
        if (dataCenter.getSpec().isParked())
            return 0;

        return cqlRoleManager.get(dataCenter, REAPER_ROLE.getUsername()).isReconcilied() ? 1 : 0;
    }


    public Single<Boolean> createOrReplaceReaperObjects(final DataCenter dataCenter) throws ApiException, StrapkopException, IOException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();

        final Map<String, String> labels = reaperLabels(dataCenter);

        String datacenterGeneration = dataCenter.getMetadata().getGeneration().toString();
        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(reaperName(dataCenter))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, datacenterGeneration);

        // abort deployment replacement if it is already up to date (according to the annotation datacenter-generation and to spec.replicas)
        // this is important because otherwise it generate a "larsen" : deployment replace -> k8s event -> reconciliation -> deployment replace...
        Boolean deployRepear = true;
        try {
            final V1Deployment existingDeployment = appsApi.readNamespacedDeployment(meta.getName(), meta.getNamespace(), null, null, null);
            final String reaperDatacenterGeneration = existingDeployment.getMetadata().getAnnotations().get(OperatorLabels.DATACENTER_GENERATION);

            if (reaperDatacenterGeneration == null) {
                throw new StrapkopException(String.format("reaper deployment %s miss the annotation datacenter-generation", meta.getName()));
            }

            if (reaperDatacenterGeneration.equals(datacenterGeneration)) {
                deployRepear = false;
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        // no need to update repear deployment, already deployed with the current datacenter-generation annotation
        if (!deployRepear)
            return Single.just(false);


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

        if (dataCenterSpec.getPriorityClassName() != null) {
            podSpec.setPriorityClassName(dataCenterSpec.getPriorityClassName());
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
        String contactPoint = OperatorNames.nodesService(dataCenter) + "." + dataCenter.getMetadata().getNamespace() + ".svc.cluster.local";
        container
                .name("reaper")
                .image(dataCenterSpec.getReaper().getImage())
                .imagePullPolicy("Always")
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
                .addEnvItem(new V1EnvVar().name("REAPER_DATACENTER_AVAILABILITY").value("LOCAL"))
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_JMX_AUTH_PASSWORD")
                        .valueFrom(new V1EnvVarSource()
                                .secretKeyRef(new V1SecretKeySelector()
                                        .name(OperatorNames.clusterSecret(dataCenter))
                                        .key(DataCenterUpdateAction.KEY_JMX_PASSWORD)
                                )
                        )
                )
                .addEnvItem(new V1EnvVar().name("REAPER_JMX_AUTH_USERNAME").value("cassandra"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTH_USER").value("admin"))
                .addEnvItem(new V1EnvVar()
                        .name("REAPER_AUTH_PASSWORD")
                        .valueFrom(new V1EnvVarSource()
                                .secretKeyRef(new V1SecretKeySelector()
                                        .name(reaperSecretName(dataCenter))
                                        .key(REAPER_ADMIN_PASSWORD_KEY)
                                )
                        )
                )
                .addEnvItem(new V1EnvVar().name("REAPER_STORAGE_TYPE").value("cassandra"))
                .addEnvItem(new V1EnvVar().name("REAPER_CASS_CLUSTER_NAME").value(dataCenterSpec.getClusterName()))
                .addEnvItem(new V1EnvVar().name("REAPER_CASS_CONTACT_POINTS").value("[ \"" + contactPoint + "\" ]"))
                .addEnvItem(new V1EnvVar().name("REAPER_CASS_PORT").value(dataCenterSpec.getNativePort().toString()))
                .addEnvItem(new V1EnvVar().name("REAPER_CASS_KEYSPACE").value("reaper_db"))
                .addEnvItem(new V1EnvVar().name("REAPER_CASS_LOCAL_DC").value(dataCenterSpec.getDatacenterName()))
                .addEnvItem(new V1EnvVar().name("JWT_SECRET").value(Base64.getEncoder().encodeToString(dataCenterSpec.getReaper().getJwtSecret().getBytes())))
                .addEnvItem(new V1EnvVar().name("REAPER_LOGGING_ROOT_LEVEL").value(dataCenterSpec.getReaper().getLoggingLevel() == null ? "INFO" : dataCenterSpec.getReaper().getLoggingLevel()))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_ENABLED").value("false"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_INITIAL_DELAY_PERIOD").value("PT60S"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_PERIOD_BETWEEN_POLLS").value("PT10M"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_TIME_BEFORE_FIRST_SCHEDULE").value("PT5M"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_SCHEDULE_SPREAD_PERIOD").value("PT6H"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_EXCLUDED_KEYSPACES").value("[]"))
                .addEnvItem(new V1EnvVar().name("REAPER_AUTO_SCHEDULING_EXCLUDED_CLUSTERS").value("[]"))
        ;

        if (dataCenterSpec.getExternalDns() != null && dataCenterSpec.getExternalDns().getEnabled() == true) {
            container
                    .addEnvItem(new V1EnvVar().name("REAPER_CASS_ADDRESS_TRANSLATOR_ENABLED").value("true"))
                    .addEnvItem(new V1EnvVar().name("REAPER_CASS_ADDRESS_TRANSLATOR_TYPE").value("kubernetesDnsTranslator"))
                    .addEnvItem(new V1EnvVar().name("JMX_ADDRESS_TRANSLATOR_TYPE").value("kubernetesDnsTranslator"));
        }

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
        final V1ObjectMeta ingressMeta = new V1ObjectMeta()
                .name(reaperName(dataCenter))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem(OperatorLabels.DATACENTER_GENERATION, datacenterGeneration);
        if (dataCenterSpec.getReaper().getIngressAnnotations() != null && !dataCenterSpec.getReaper().getIngressAnnotations().isEmpty()) {
            dataCenterSpec.getReaper().getIngressAnnotations().entrySet().stream()
                    .map(e -> ingressMeta.putAnnotationsItem(e.getKey(), e.getValue()));
        }
        final ExtensionsV1beta1Ingress ingress;
        if (!Strings.isNullOrEmpty(dataCenterSpec.getReaper().getIngressSuffix())) {
            String baseHostname = dataCenterSpec.getReaper().getIngressSuffix();
            String reaperAppHost = "reaper-" + baseHostname;
            String reaperAdminHost = "admin-reaper-" + baseHostname;
            logger.info("Creating reaper ingress for reaperAppHost={} reaperAdminHost={}", reaperAppHost, reaperAdminHost);
            ingress = new ExtensionsV1beta1Ingress()
                    .metadata(ingressMeta)
                    .spec(new ExtensionsV1beta1IngressSpec()
                            .addRulesItem(new ExtensionsV1beta1IngressRule()
                                    .host(reaperAppHost)
                                    .http(new ExtensionsV1beta1HTTPIngressRuleValue()
                                            .addPathsItem(new ExtensionsV1beta1HTTPIngressPath()
                                                    .path("/")
                                                    .backend(new ExtensionsV1beta1IngressBackend()
                                                            .serviceName(reaperName(dataCenter))
                                                            .servicePort(new IntOrString(APP_SERVICE_PORT)))
                                            )
                                    )
                            )
                            .addTlsItem(new ExtensionsV1beta1IngressTLS().addHostsItem(reaperAppHost))
                            .addRulesItem(new ExtensionsV1beta1IngressRule()
                                    .host(reaperAdminHost)
                                    .http(new ExtensionsV1beta1HTTPIngressRuleValue()
                                            .addPathsItem(new ExtensionsV1beta1HTTPIngressPath()
                                                    .path("/")
                                                    .backend(new ExtensionsV1beta1IngressBackend()
                                                            .serviceName(reaperName(dataCenter))
                                                            .servicePort(new IntOrString(ADMIN_SERVICE_PORT)))
                                            )
                                    )
                            )
                            .addTlsItem(new ExtensionsV1beta1IngressTLS().addHostsItem(reaperAdminHost))

                    );
        } else {
            ingress = null;
        }

        Completable todo = createReaperSecretIfNotExists(dataCenter).ignoreElement()
                .andThen(k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment).ignoreElement())
                .andThen(k8sResourceUtils.createOrReplaceNamespacedService(service).ignoreElement());
        if (ingress != null)
            todo = todo.andThen(k8sResourceUtils.createOrReplaceNamespacedIngress(ingress).ignoreElement());

        return todo.toSingleDefault(false);
    }

    private String reaperSecretName(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-reaper", dataCenter);
    }

    /**
     * Called form the ReaperPodHandler when repaer pod is ready.
     * As soon as reaper_db keyspace is created, this function try to ping the reaper api and, if success, register the datacenter.
     * THe registration is done only once. If the datacenter is unregistered by the user, it will not register it again automatically.
     */
    public Completable register(DataCenter dc) throws StrapkopException, ApiException, MalformedURLException {
        ReaperClient reaperClient = new ReaperClient(dc, this.registrationScheduler);
        return loadReaperAdminPassword(dc)
                .observeOn(registrationScheduler)
                .subscribeOn(registrationScheduler)
                .flatMap(password ->
                        reaperClient.registerCluster("admin", password)
                                .retryWhen((Flowable<Throwable> f) -> f.take(9).delay(21, TimeUnit.SECONDS))
                )
                .flatMapCompletable(bool -> {
                    if (bool) {
                        dc.getStatus().setReaperPhase(ReaperPhase.REGISTERED);
                        logger.info("dc={} cassandra-reaper successfully registred", dc.id());
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
                    dc.getStatus().setLastError(e.toString());
                    dc.getStatus().setLastErrorTime(new Date());
                    logger.error("datacenter=" + dc.id() + " error while registering in cassandra-reaper", e);
                });
    }

    // TODO: cache cluster secret to avoid loading secret again and again
    private Single<String> loadReaperAdminPassword(DataCenter dc) throws ApiException, StrapkopException {
        String reaperSecretName = reaperSecretName(dc);
        return k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), reaperSecretName).map(secret -> {
            final byte[] password = secret.getData().get(REAPER_ADMIN_PASSWORD_KEY);
            if (password == null) {
                throw new StrapkopException(String.format("secret %s does not contain reaper.admin_password", reaperSecretName));
            }
            return new String(password);
        });
    }

    private Single<V1Secret> createReaperSecretIfNotExists(DataCenter dc) throws ApiException {
        String reaperSecretName = reaperSecretName(dc);
        final V1ObjectMeta secretMetadata = new V1ObjectMeta()
                .name(reaperSecretName)
                .namespace(dc.getMetadata().getNamespace())
                .labels(OperatorLabels.datacenter(dc));

        return this.k8sResourceUtils.readOrCreateNamespacedSecret(secretMetadata, () -> {
            logger.debug("datacenter={} Creating reaper secret name={}", dc.id(), reaperSecretName);
            return new V1Secret()
                    .metadata(secretMetadata)
                    .type("Opaque")
                    // replace the default cassandra password
                    .putStringDataItem(REAPER_ADMIN_PASSWORD_KEY, UUID.randomUUID().toString());
        });
    }

    // TODO: manage removed scheduled repairs
    public Completable registerScheduledRepair(DataCenter dc) throws MalformedURLException, ApiException {
        ReaperClient reaperClient = new ReaperClient(dc, this.registrationScheduler);
        return loadReaperAdminPassword(dc)
                .observeOn(registrationScheduler)
                .subscribeOn(registrationScheduler)
                .flatMapCompletable(password -> {
                    List<CompletableSource> todoList = new ArrayList<>();
                    Map<String, ReaperScheduledRepair> scheduledRepairMap = new HashMap<>();
                    // repair system + elastic_admin_xxx + managed keyspaces
                    for (CqlKeyspace cqlKeyspace : this.cqlKeyspaceManager.get(dc).values()) {
                        scheduledRepairMap.put(cqlKeyspace.getName(), new ReaperScheduledRepair()
                                .withKeyspace(cqlKeyspace.getName())
                                .withOwner("elassandra-operator"));
                    }
                    // add explicit scheduled repair, can ovreride default settings
                    if (dc.getSpec().getReaper().getReaperScheduledRepairs() != null) {
                        dc.getSpec().getReaper().getReaperScheduledRepairs().stream().map(s -> scheduledRepairMap.put(s.getKeyspace(), s));
                    }
                    logger.debug("Submit scheduledRepair={}", scheduledRepairMap.values());
                    for (ReaperScheduledRepair reaperScheduledRepair : scheduledRepairMap.values()) {
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
                    dc.getStatus().setLastError(e.toString());
                    dc.getStatus().setLastErrorTime(new Date());
                    logger.error("datacenter={} error while registering scheduled repair", dc.id(), e);
                });
    }
}
