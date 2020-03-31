package com.strapdata.strapkop.cql;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.LoggingRetryPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.google.common.collect.ImmutableList;
import com.strapdata.cassandra.driver.KubernetesDnsAddressTranslator;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.cassandra.Authentication;
import com.strapdata.strapkop.model.k8s.cassandra.CqlStatus;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Manage cassandra role creation and update password from k8s secrets, and create associated CQL permission.
 * Role reconciliation must be made after Keyspace reconciliation.
 *
 *
 * TODO: update password when k8s secret is updated.
 */
@Singleton
public class CqlRoleManager extends AbstractManager<CqlRole> {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlRoleManager.class);

    final CoreV1Api coreApi;
    final K8sResourceUtils k8sResourceUtils;
    final AuthorityManager authorityManager;
    final OperatorConfig operatorConfig;

    Map<String, CqlRole> connectedCqlRoles = new ConcurrentHashMap<>();

    public CqlRole connectedCqlRole(DataCenter dc) {
        return this.connectedCqlRoles.getOrDefault(key(dc), CqlRole.DEFAULT_CASSANDRA_ROLE);
    }

    public CqlRole connectedCqlRole(String dcKey) {
        return this.connectedCqlRoles.getOrDefault(dcKey, CqlRole.DEFAULT_CASSANDRA_ROLE);
    }

    public CqlRoleManager(final CoreV1Api coreApi,
                          final K8sResourceUtils k8sResourceUtils,
                          final AuthorityManager authorityManager,
                          final OperatorConfig operatorConfig) {
        super();
        this.coreApi = coreApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.operatorConfig = operatorConfig;
    }

    /**
     * Idempotent credentials reconciliation
     *
     * @param dataCenter
     * @throws ApiException
     * @throws StrapkopException
     */
    public Single<Boolean> reconcileRole(DataCenter dataCenter, Boolean status, CqlSessionSupplier sessionSupplier, PluginRegistry pluginRegistry) {
        return Single.just(status)
                .map(doUpdateStatus -> {
                    if (Authentication.NONE.equals(dataCenter.getSpec().getAuthentication()))
                        return doUpdateStatus;

                    addIfAbsent(dataCenter, CqlRole.CASSANDRA_ROLE.username, () -> CqlRole.CASSANDRA_ROLE.duplicate());
                    addIfAbsent(dataCenter, CqlRole.ADMIN_ROLE.username, () -> CqlRole.ADMIN_ROLE.duplicate());
                    addIfAbsent(dataCenter, CqlRole.STRAPKOP_ROLE.username, () -> CqlRole.STRAPKOP_ROLE.duplicate());

                    for(Plugin plugin : pluginRegistry.plugins()) {
                        if (plugin.isActive(dataCenter)) {
                            try {
                                plugin.syncRoles(CqlRoleManager.this, dataCenter);
                            } catch(Exception e) {
                                logger.warn("datacenter={} Failed to syncRoles for plugin={}", dataCenter.id(), plugin.getClass().getName());
                            }
                        }
                    }
                    return doUpdateStatus;
                })
                .flatMap(doUpdateStatus -> {
                    // now we are sure authentication is required and cql connection has been set
                    logger.info("datacenter={} reconcile roles", dataCenter.id());
                    List<CompletableSource> todoList = new ArrayList<>();
                    if (get(dataCenter) != null) {
                        for (CqlRole role : get(dataCenter).values()) {
                            if (!role.isApplied()) {
                                try {
                                    doUpdateStatus = true;
                                    todoList.add(role.createOrUpdateRole(dataCenter, k8sResourceUtils, sessionSupplier).ignoreElement());
                                } catch (Exception ex) {
                                    logger.error("datacenter={} Cannot load password or apply for role={} error={}",
                                            dataCenter.id(), role.getUsername(), ex.getMessage());
                                }
                            }
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(doUpdateStatus);
                });
    }

    /**
     * @param dc the datacenter to connect  to
     * @return
     * @throws DriverException
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    public Single<Tuple2<Cluster,Session>> connect(final DataCenter dc, final DataCenterStatus dcStatus) throws Exception {

        addIfAbsent(dc, CqlRole.CASSANDRA_ROLE.username, () -> CqlRole.CASSANDRA_ROLE.duplicate());
        addIfAbsent(dc, CqlRole.ADMIN_ROLE.username, () -> CqlRole.ADMIN_ROLE.duplicate());
        addIfAbsent(dc, CqlRole.STRAPKOP_ROLE.username, () -> CqlRole.STRAPKOP_ROLE.duplicate());

        return Single.fromCallable(new Callable<Tuple2<Cluster,Session>>() {
                @Override
                public Tuple2<Cluster,Session> call() throws Exception {
                    logger.debug("datacenter={} Creating a new CQL connection", dc.id());
                    if (dc.getSpec().getAuthentication().equals(Authentication.NONE))
                        return connect(dc, dcStatus, Optional.empty()).blockingGet();

                    // WARNING: get the role copy for the current dc
                    List<CqlRole> roles = ImmutableList.of(
                            get(dc, CqlRole.ADMIN_ROLE.username),
                            get(dc, CqlRole.CASSANDRA_ROLE.username),
                            get(dc, CqlRole.STRAPKOP_ROLE.username),
                            CqlRole.DEFAULT_CASSANDRA_ROLE
                    );

                    Tuple2<Cluster,Session> rootClusterSession = null;
                    CqlRole connectedRole = null;
                    Exception lastException = null;
                    for (CqlRole role : roles) {
                        try {
                            logger.debug("datacenter={} Loading secret for role={}", dc.id(), role);
                            role.loadPassword(dc, k8sResourceUtils).blockingGet();
                            logger.debug("datacenter={} Connecting with role={}", dc.id(), role);
                            rootClusterSession = connect(dc, dcStatus, Optional.of(role)).blockingGet();
                            logger.debug("datacenter={} Connected with role={}", dc.id(), connectedRole);
                            connectedRole = role;
                            connectedCqlRoles.put(key(dc), role);
                            break;
                        } catch (AuthenticationException e) {
                            // authentication failed
                            logger.debug("datacenter={} Authentication failed with role={} from secret={}",
                                    dc.id(), role.username, (role.secretKey == null) ? null : role.secret(dc));
                            lastException = e;
                        } catch (ApiException e) {
                            // cannot load k8s secret
                            logger.warn("datacenter={} Cannot load secret in for role={} from secret={}",
                                    dc.id(), role.username, (role.secretKey == null) ? null : role.secret(dc));
                            lastException = e;
                        } catch (StrapkopException e) {
                            // password contains illegal caracters
                            logger.warn("datacenter={} Bad password for role={} from secret={}",
                                    dc.id(), role.username, role.secret(dc));
                            lastException = e;
                        } catch(DriverException e) {
                            logger.warn("datacenter=" + dc.id() + " Driver exception:" + e.getMessage(), e);
                            lastException = e;
                        } catch(IllegalArgumentException e) {
                            logger.warn("datacenter=" + dc.id() + " No pod available for a CQL connection:" + e.getMessage());
                            lastException = e;
                        } catch(Exception e) {
                            logger.debug("datacenter=" + dc.id() + " Unexpected exception:" + e.getMessage(), e);
                            lastException = e;
                        }
                    }

                    if (connectedRole == null) {
                        // auth failed for all roles
                        List<String> r = roles.stream().map(CqlRole::getUsername).collect(Collectors.toList());
                        logger.warn("datacenter={} Cannot connect with roles={}", dc.id(), r);
                        dc.getStatus().setCqlStatus(CqlStatus.ERRORED);
                        dc.getStatus().setCqlStatusMessage("Authentication failed with roles=" + r);
                        if (lastException != null) {
                            logger.warn("datacenter=" + dc.id() + " Authentication failed with roles=" + r + " error:" + lastException.getMessage());
                        }
                        throw lastException;
                    }

                    if (!connectedRole.username.equals(CqlRole.STRAPKOP_ROLE.username)) {
                        // create+update roles
                        final Session currentSession = rootClusterSession._2;
                        for (CqlRole role : ImmutableList.of(
                                get(dc, CqlRole.ADMIN_ROLE.username),
                                get(dc, CqlRole.CASSANDRA_ROLE.username),
                                get(dc, CqlRole.STRAPKOP_ROLE.username)
                        )) {
                            try {
                                role.createOrUpdateRole(dc, k8sResourceUtils, new CqlSessionSupplier() {
                                    @Override
                                    public Single<Session> getSession(DataCenter dc) throws Exception {
                                        return Single.just(currentSession);
                                    }

                                    @Override
                                    public Single<Session> getSessionWithSchemaAgreed(DataCenter dataCenter) throws Exception {
                                        return Single.just(currentSession);
                                    }

                                    @Override
                                    public void close() {
                                        // do not close it now
                                    }
                                }).blockingGet();
                            } catch (Exception e) {
                                logger.error("datacenter={} Cannot CreateOrUpdate role={}", dc.id(), role, e);
                            }
                        }

                        // reconnect with strapkop and close root session
                        CqlRole strakopRole = get(dc, CqlRole.STRAPKOP_ROLE.username);
                        try {
                            Tuple2<Cluster, Session> strapkopConnection = connect(dc, dcStatus, Optional.of(strakopRole)).blockingGet();
                            connectedCqlRoles.put(key(dc), strakopRole);
                            try {
                                logger.debug("Closing root session cluster={}", rootClusterSession._1.getClusterName());
                                rootClusterSession._1.close();
                            } catch(Exception e) {
                                logger.warn("datacenter="+dc.id()+" Failed to close root session:" + e.getMessage(), e);
                            }
                            return strapkopConnection;
                        } catch(Exception e) {
                            logger.error("datacenter="+dc.id()+" Failed to reconnect with the operator role="+strakopRole+" :"+e.getMessage(), e);
                        }
                    }
                    return rootClusterSession;
                }
            });
    }

    private Single<Tuple2<Cluster, Session>> connect(final DataCenter dc, final DataCenterStatus dataCenterStatus, Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        return Single.just(createClusterObject(dc, optionalCqlRole))
                .flatMap(cluster -> Single.fromFuture(cluster.connectAsync())
                        .flatMap(session -> {
                            dataCenterStatus.setCqlStatus(CqlStatus.ESTABLISHED);
                            dataCenterStatus.setCqlStatusMessage("Connected to cluster=[" + cluster.getClusterName() + "]" +
                                    ((optionalCqlRole.isPresent()) ? (" with role=[" + optionalCqlRole.get().username+"] secret=["+optionalCqlRole.get().secret(dc)+"]") : ""));
                            logger.debug("Connected to dc=" + dc.id() + ((optionalCqlRole.isPresent()) ? (" with role=" + optionalCqlRole.get().username+" secret="+optionalCqlRole.get().secret(dc)) : ""));
                            return Single.just(new Tuple2<>(cluster, session));
                        })
                        .doOnError(t -> {
                            logger.warn("datacenter={} error={}", dc.id(), t);
                            cluster.closeAsync();
                        }));
    }

    private Cluster createClusterObject(final DataCenter dc, final Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        // check the number of available node to adapt the ConsistencyLevel otherwise creating a DC with more than 1 node (or park a DC) isn't possible
        // because licence can't be checked (UnavailableException: Not enough replicas available for query at consistency LOCAL_QUORUM (2 required but only 1 alive))

        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_ONE))
                .withPoolingOptions(new PoolingOptions()
                        // see https://dzone.com/articles/tuning-datastax-java-driver-for-cassandra
                        .setConnectionsPerHost(HostDistance.REMOTE, 0, 0)
                        .setCoreConnectionsPerHost(HostDistance.REMOTE, 0)
                        .setConnectionsPerHost(HostDistance.LOCAL,1, 1)
                        .setCoreConnectionsPerHost(HostDistance.LOCAL,1)
                )
                .withMaxSchemaAgreementWaitSeconds(operatorConfig.getMaxSchemaAgreementWaitSeconds())
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .withUsedHostsPerRemoteDc(0)
                                .build()))
                .withRetryPolicy(new LoggingRetryPolicy(StrapkopRetryPolicy.INSTANCE));

        if (dc.getSpec().getHostNetworkEnabled() || dc.getSpec().getHostPortEnabled()) {
            // if cluster has public broadcast IPs, the translator retreive internal k8s IP addresses
            builder.withAddressTranslator(new KubernetesDnsAddressTranslator());
        }

        // contact local nodes is bootstrapped or first DC in the cluster
        boolean hasSeedBootstrapped = dc.getStatus().getBootstrapped();
        if (hasSeedBootstrapped ||
                ((dc.getSpec().getRemoteSeeds() == null || dc.getSpec().getRemoteSeeds().isEmpty()) &&
                        (dc.getSpec().getRemoteSeeders() == null || dc.getSpec().getRemoteSeeders().isEmpty()))) {
            String contactPoint = OperatorNames.nodesService(dc) + "." + dc.getMetadata().getNamespace() + ".svc.cluster.local";
            try {
                logger.debug("datacenter={} add local seed={}", dc.id(), contactPoint);
                builder.addContactPoint(contactPoint);
            } catch(IllegalArgumentException e) {
                if (e.getCause() != null && e.getCause() instanceof  java.net.UnknownHostException) {
                    // ignore DNS resolution failure because dc removed....
                    logger.debug("datacenter={} seed={} can't be added due to UnknownHostException, DC removed", dc.id(), contactPoint);
                } else {
                    throw e;
                }
            }
        }

        if (Objects.equals(dc.getSpec().getSsl(), Boolean.TRUE)) {
            builder.withSSL(getSSLOptions(dc.getMetadata().getNamespace()));
        }

        if (optionalCqlRole.isPresent()) {
            logger.trace("datacenter={} username={} password={}", dc.id(), optionalCqlRole.get().getUsername(), optionalCqlRole.get().secret(dc));
            builder.withCredentials(
                    optionalCqlRole.get().getUsername(),
                    optionalCqlRole.get().getPassword()
            );
        }

        builder.withoutMetrics(); // disable metric collection
        return builder.build();
    }

    private SSLOptions getSSLOptions(String namespace) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace);
        SslContext sslContext = SslContextBuilder
                .forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(new ByteArrayInputStream(ca.getCertificateChainAsString().getBytes(StandardCharsets.UTF_8)))
                .build();
        return new RemoteEndpointAwareNettySSLOptions(sslContext);
    }
}
