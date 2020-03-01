package com.strapdata.strapkop.cql;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.LoggingRetryPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.cassandra.Authentication;
import com.strapdata.strapkop.model.k8s.cassandra.CqlStatus;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
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
import io.reactivex.Single;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
    final PluginRegistry pluginRegistry;
    final AuthorityManager authorityManager;

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
                          final PluginRegistry pluginRegistry) {
        super();
        this.coreApi = coreApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
        this.pluginRegistry = pluginRegistry;
    }

    /**
     * Idempotent credentials reconciliation
     *
     * @param dataCenter
     * @throws ApiException
     * @throws StrapkopException
     */
    public Completable reconcileRole(DataCenter dataCenter, CqlSessionSupplier sessionSupplier) {
        return Completable.fromRunnable(new Runnable() {
            @Override
            public void run() {
                if (Authentication.NONE.equals(dataCenter.getSpec().getAuthentication()))
                    return;

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

                // now we are sure authentication is required and cql connection has been set
                logger.info("datacenter={} reconcile roles", dataCenter.id());
                for(CqlRole role : get(dataCenter).values()) {
                    if (!role.isApplied()) {
                        try {
                            role.createOrUpdateRole(dataCenter, k8sResourceUtils, sessionSupplier).blockingGet();
                        } catch (Exception ex) {
                            logger.error("datacenter={} Cannot load password or apply for role={} error={}",
                                    dataCenter.id(), role.getUsername(), ex.getMessage());
                        }
                    }
                }
            }
        });
    }

    public void markRolesAsUnapplied(final DataCenter dc) {
        remove(dc);
        this.connectedCqlRoles.remove(key(dc));
        logger.debug("datacenter={} roles cleared", dc.id());
    }

    /**
     * @param dc the datacenter to connect  to
     * @return
     * @throws DriverException
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    public Single<Tuple2<Cluster,Session>> connect(final DataCenter dc) throws Exception {

        addIfAbsent(dc, CqlRole.CASSANDRA_ROLE.username, () -> CqlRole.CASSANDRA_ROLE.duplicate());
        addIfAbsent(dc, CqlRole.ADMIN_ROLE.username, () -> CqlRole.ADMIN_ROLE.duplicate());
        addIfAbsent(dc, CqlRole.STRAPKOP_ROLE.username, () -> CqlRole.STRAPKOP_ROLE.duplicate());

        return Single.fromCallable(new Callable<Tuple2<Cluster,Session>>() {
                @Override
                public Tuple2<Cluster,Session> call() throws Exception {
                    logger.debug("datacenter={} Creating a new CQL connection", dc.id());
                    if (dc.getSpec().getAuthentication().equals(Authentication.NONE))
                        return connect(dc, Optional.empty());

                    // WARNING: get the role copy for the current dc
                    List<CqlRole> roles = ImmutableList.of(
                            get(dc, CqlRole.ADMIN_ROLE.username),
                            get(dc, CqlRole.CASSANDRA_ROLE.username),
                            get(dc, CqlRole.STRAPKOP_ROLE.username),
                            CqlRole.DEFAULT_CASSANDRA_ROLE
                    );

                    Tuple2<Cluster,Session> tuple = null;
                    CqlRole connectedRole = null;
                    Exception lastException = null;
                    for (CqlRole role : roles) {
                        try {
                            role.loadPassword(dc, k8sResourceUtils).blockingGet();
                            tuple = connect(dc, Optional.of(role));
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
                            if (tuple != null) {
                                CqlSessionSupplier.closeQuietly(tuple._2);
                                CqlSessionSupplier.closeQuietly(tuple._1);
                            }
                            logger.warn("datacenter=" + dc.id() + " Authentication failed with roles=" + r + " error:" + lastException.getMessage(), lastException);
                            throw lastException;
                        }
                    }

                    if (!connectedRole.username.equals(CqlRole.STRAPKOP_ROLE.username)) {
                        // create+update roles
                        final Session currentSesssion = tuple._2;
                        for (CqlRole role : ImmutableList.of(
                                get(dc, CqlRole.ADMIN_ROLE.username),
                                get(dc, CqlRole.CASSANDRA_ROLE.username),
                                get(dc, CqlRole.STRAPKOP_ROLE.username)
                        )) {
                            try {
                                role.createOrUpdateRole(dc, k8sResourceUtils, new CqlSessionSupplier() {
                                    @Override
                                    public Single<Session> getSession(DataCenter dc) throws Exception {
                                        return Single.just(currentSesssion);
                                    }
                                }).blockingGet();
                            } catch (Exception e) {
                                logger.error("datacenter={} Cannot CreateOrUpdate role={}", dc.id(), role, e);
                            }
                        }

                        // reconnect with strapkop
                        CqlRole strakopRole = get(dc, CqlRole.STRAPKOP_ROLE.username);
                        try {
                            Tuple2<Cluster, Session> strapkopConnection = connect(dc, Optional.of(strakopRole));
                            connectedCqlRoles.put(key(dc), strakopRole);
                            return strapkopConnection;
                        } catch(Exception e) {
                            logger.error("datacenter="+dc.id()+" Failed to reconnect with the operator role="+strakopRole+" :"+e.getMessage(), e);
                        } finally {
                            // connection with strapkop user succeeded
                            // we close the previous session to avoid non relevant authentication exception
                            CqlSessionSupplier.closeQuietly(currentSesssion);
                            CqlSessionSupplier.closeQuietly(tuple._1);// close also the cluster instance that generates the session
                        }
                    }
                    return tuple;
                }
            });
    }

    private Tuple2<Cluster, Session> connect(final DataCenter dc, Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        Cluster cluster = null;
        Session session = null;
        try {
            cluster = createClusterObject(dc, optionalCqlRole);
            session = cluster.connect();
        } catch (DriverException | ExecutionException | InterruptedException | IllegalArgumentException e) {
            // on DriverException try to close the session to avoid cnx leak or non relevant ERROR log message
            CqlSessionSupplier.closeQuietly(session);
            CqlSessionSupplier.closeQuietly(cluster); // also close cluster to free the connection that check the cluster state
            // rethrow the exception to manage the error in a proper way
            throw e;
        }
        dc.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
        dc.getStatus().setCqlStatusMessage("Connected to cluster=[" + cluster.getClusterName() + "]" +
                ((optionalCqlRole.isPresent()) ? (" with role=[" + optionalCqlRole.get().username+"] secret=["+optionalCqlRole.get().secret(dc)+"]") : ""));
        logger.debug("Connected to dc=" + dc.id() + ((optionalCqlRole.isPresent()) ? (" with role=" + optionalCqlRole.get().username+" secret="+optionalCqlRole.get().secret(dc)) : ""));
        return new Tuple2<>(cluster, session);
    }

    private Cluster createClusterObject(final DataCenter dc, final Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        // check the number of available node to adapt the ConsistencyLevel otherwise creating a DC with more than 1 node (or park a DC) isn't possible
        // because licence can't be checked (UnavailableException: Not enough replicas available for query at consistency LOCAL_QUORUM (2 required but only 1 alive))
        final long availableNodes = dc.getStatus().getRackStatuses()
                .values().stream().map(status -> status.getJoinedReplicas()).reduce(0, Integer::sum);
        final long parkedNodes = dc.getStatus().getRackStatuses()
                .values().stream().map(status -> status.getParkedReplicas()).reduce(0, Integer::sum);

        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_ONE))
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()))
                .withRetryPolicy(new LoggingRetryPolicy(StrapkopRetryPolicy.INSTANCE));

        // add remote seeds to contact points to be able to adjust RF of system keyspace before starting the first local node.
        /***** Local DC connection ONLY *******
        if (dc.getSpec().getRemoteSeeds() != null)
            for(String remoteSeed : dc.getSpec().getRemoteSeeds()) {
                logger.debug("datacenter={} Add remote seed={}", dc.id(), remoteSeed);
                builder.addContactPoint(remoteSeed);
            }

        // add seeds from remote seeders.
        if (dc.getSpec().getRemoteSeeders() != null) {
            for(String remoteSeeder : dc.getSpec().getRemoteSeeders()) {
                try {
                    for(InetAddress addr : ElassandraOperatorSeedProvider.seederCall(remoteSeeder)) {
                        logger.debug("datacenter={} Add remote seed={} from seeder={}",
                                dc.id(), addr.getHostAddress(), remoteSeeder);
                        builder.addContactPoint(addr.getHostAddress());
                    }
                } catch (Exception e) {
                    logger.error("datacenter="+dc.id()+" Seeder error", e);
                }
            }
        }
        */

        // contact local nodes is bootstrapped or first DC in the cluster
        boolean hasSeedBootstrapped = dc.getStatus().getRackStatuses().values().stream().anyMatch(s -> s.getJoinedReplicas() > 0);
        if (hasSeedBootstrapped ||
                ((dc.getSpec().getRemoteSeeds() == null || dc.getSpec().getRemoteSeeds().isEmpty()) && (dc.getSpec().getRemoteSeeders() == null || dc.getSpec().getRemoteSeeders().isEmpty()))) {
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
