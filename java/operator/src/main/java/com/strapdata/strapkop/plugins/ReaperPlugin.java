package com.strapdata.strapkop.plugins;

import com.datastax.driver.core.Session;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.strapkop.cql.*;
import com.strapdata.strapkop.StrapkopException;
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
import io.reactivex.schedulers.Schedulers;

import javax.inject.Singleton;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manage reaper deployment
 */
@Singleton
public class ReaperPlugin extends AbstractPlugin {

    private String reaperAdminPassword = null; // keep password to avoid secret reloading.

    public static final String APP_SERVICE_NAME = "app";
    public static final String ADMIN_SERVICE_NAME = "admin";
    public static final int APP_SERVICE_PORT = 8080;      // the webui
    public static final int ADMIN_SERVICE_PORT = 8081;    // the REST API

    public ReaperPlugin(final ApplicationContext context,
                        K8sResourceUtils k8sResourceUtils,
                        AuthorityManager authorityManager,
                        CqlConnectionManager cqlConnectionManager,
                        CoreV1Api coreApi,
                        AppsV1Api appsApi) {
        super(context, k8sResourceUtils, authorityManager, cqlConnectionManager, coreApi, appsApi);
    }

    public static final CqlKeyspace REAPER_KEYSPACE = new CqlKeyspace("reaper_db", 3) {
        @Override
        public CqlKeyspace createIfNotExistsKeyspace(DataCenter dataCenter, Session session) {
            CqlKeyspace ks = super.createIfNotExistsKeyspace(dataCenter, session);
            dataCenter.getStatus().setReaperStatus(ReaperStatus.KEYSPACE_CREATED);
            return ks;
        }
    };

    @Override
    public void syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        if (dataCenter.getSpec().getReaperEnabled()) {
            cqlKeyspaceManager.addIfAbsent(dataCenter, REAPER_KEYSPACE.getName(), () -> REAPER_KEYSPACE);
        } else {
            cqlKeyspaceManager.remove(dataCenter, REAPER_KEYSPACE.getName());
        }
    }

    public static final CqlRole REAPER_ROLE = new CqlRole()
            .withUsername("reaper")
            .withSecretKey("cassandra.reaper_password")
            .withSuperUser(false)
            .withApplied(false)
            .withGrantStatements(ImmutableList.of("GRANT ALL PERMISSIONS ON KEYSPACE reaper_db TO reaper"))
            .withPostCreateHandler(ReaperPlugin::postCreateReaper);

    public static void postCreateReaper(DataCenter dataCenter, final Session session) throws Exception {
        dataCenter.getStatus().setReaperStatus(ReaperStatus.ROLE_CREATED);
        logger.debug("reaper role created for dc={}, ReaperStatus=ROLE_CREATED", dataCenter.getMetadata().getName());
    }

    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return dataCenter.getSpec().getReaperEnabled();
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
    public void reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        if (dataCenter.getSpec().getReaperEnabled()) {
            createOrReplaceReaperObjects(dataCenter);
        } else {
            delete(dataCenter);
        }
    }

    @Override
    public void delete(final DataCenter dataCenter) throws ApiException {
        final String reaperLabelSelector = OperatorLabels.toSelector(reaperLabels(dataCenter));
        k8sResourceUtils.deleteIngress(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector);
        k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, reaperLabelSelector);
        k8sResourceUtils.deleteDeployment(reaperName(dataCenter), dataCenter.getMetadata().getNamespace());
    }


    /**
     * @return The number of reaper pods depending on ReaperStatus
     */
    private int reaperReplicas(final DataCenter dataCenter) {
        switch(dataCenter.getStatus().getReaperStatus()) {
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


    public void createOrReplaceReaperObjects(final DataCenter dataCenter) throws ApiException, StrapkopException {
        final V1ObjectMeta dataCenterMetadata = dataCenter.getMetadata();
        final DataCenterSpec dataCenterSpec = dataCenter.getSpec();
        final DataCenterStatus dataCenterStatus = dataCenter.getStatus();

        final Map<String, String> labels = reaperLabels(dataCenter);

        final V1ObjectMeta meta = new V1ObjectMeta()
                .name(reaperName(dataCenter))
                .namespace(dataCenterMetadata.getNamespace())
                .labels(labels)
                .putAnnotationsItem("datacenter-generation", dataCenter.getMetadata().getGeneration().toString());

        final V1Container container = new V1Container();

        final V1PodSpec podSpec = new V1PodSpec()
                .addContainersItem(container);

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
                                        .key("cassandra.jmx_password")
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
                                        .name(OperatorNames.clusterSecret(dataCenter))
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
                                            .key("cassandra.reaper_password")
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
                    )
            );
            container
                    .addEnvItem(new V1EnvVar()
                            .name("REAPER_CASS_NATIVE_PROTOCOL_SSL_ENCRYPTION_ENABLED")
                            .value("true")
                    )
                    .addEnvItem(new V1EnvVar()
                            .name("JAVA_OPTS")
                            .value("-Djavax.net.ssl.trustStore=/truststore/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit")
                    )
                    .addVolumeMountsItem(new V1VolumeMount()
                            .mountPath("/truststore")
                            .name("truststore")
                    );
        } else {
            container.addEnvItem(new V1EnvVar()
                    .name("REAPER_CASS_NATIVE_PROTOCOL_SSL_ENCRYPTION_ENABLED")
                    .value("false")
            );
        }

        // create reaper service
        final V1Service service = new V1Service()
                .metadata(meta)
                .spec(new V1ServiceSpec()
                        .type("ClusterIP")
                        .addPortsItem(new V1ServicePort().name(APP_SERVICE_NAME).port(APP_SERVICE_PORT))
                        .addPortsItem(new V1ServicePort().name(ADMIN_SERVICE_NAME).port(ADMIN_SERVICE_PORT))
                        .selector(labels)
                );
        k8sResourceUtils.createOrReplaceNamespacedService(service);

        // create reaper ingress
        String ingressDomain = System.getenv("INGRESS_DOMAIN");
        if (!Strings.isNullOrEmpty(ingressDomain)) {
            String reaperAppHost = "reaper-" + dataCenterSpec.getClusterName() + "-" + dataCenterSpec.getDatacenterName() + "." + ingressDomain;
            String reaperAdminHost = "admin-reaper-" + dataCenterSpec.getClusterName() + "-" + dataCenterSpec.getDatacenterName() + "." + ingressDomain;
            logger.trace("Creating reaper ingress for host={}", reaperAppHost);
            final V1beta1Ingress ingress = new V1beta1Ingress()
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
            k8sResourceUtils.createOrReplaceNamespacedIngress(ingress);
        }

        // abort deployment replacement if it is already up to date (according to the annotation datacenter-generation and to spec.replicas)
        // this is important because otherwise it generate a "larsen" : deployment replace -> k8s event -> reconciliation -> deployment replace...
        try {
            final V1Deployment existingDeployment = appsApi.readNamespacedDeployment(meta.getName(), meta.getNamespace(), null, null, null);
            final String datacenterGeneration = existingDeployment.getMetadata().getAnnotations().get("datacenter-generation");

            if (datacenterGeneration == null) {
                throw new StrapkopException(String.format("reaper deployment %s miss the annotation datacenter-generation", meta.getName()));
            }

            if (Objects.equals(Long.parseLong(datacenterGeneration), dataCenterMetadata.getGeneration()) &&
                    Objects.equals(existingDeployment.getSpec().getReplicas(), deployment.getSpec().getReplicas())) {
                return;
            }
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }

        k8sResourceUtils.createOrReplaceNamespacedDeployment(deployment);

        // reconcile reaper cluster registration
        reconcileReaperRegistration(dataCenter);
    }

    /**
     * As soon as reaper_db keyspace is created, this function try to ping the reaper api and, if success, register the datacenter.
     * THe registration is done only once. If the datacenter is unregistered by the user, it will not register it again automatically.
     */
    private void reconcileReaperRegistration(DataCenter dc) throws StrapkopException, ApiException {

        if (!ReaperStatus.REGISTERED.equals(dc.getStatus().getReaperStatus()) && (
                (dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperStatus.KEYSPACE_CREATED.equals(dc.getStatus().getReaperStatus())) ||
                        !dc.getSpec().getAuthentication().equals(Authentication.NONE) && ReaperStatus.ROLE_CREATED.equals(dc.getStatus().getReaperStatus())
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
                    dc.getStatus().setReaperStatus(ReaperStatus.REGISTERED);
                    logger.info("registered dc={} in cassandra-reaper", dc.getMetadata().getName());
                }
            }
            catch (Exception e) {
                dc.getStatus().setLastErrorMessage(e.getMessage());
                logger.error("error while registering dc={} in cassandra-reaper", dc.getMetadata().getName(), e);
            }
        }
    }

    // TODO: cache cluster secret to avoid loading secret again and again
    private String loadReaperAdminPassword(DataCenter dc) throws ApiException, StrapkopException {
        final String secretName = OperatorNames.clusterSecret(dc);
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
