package com.strapdata.strapkop.cql;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.google.common.collect.ImmutableList;
import com.strapdata.cassandra.k8s.ElassandraOperatorSeedProvider;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.ssl.AuthorityManager;
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
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
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
                            logger.warn("Failed to syncRoles for plugin={}", plugin.getClass().getName());
                        }
                    }
                }

                // now we are sure authentication is required and cql connection has been set
                logger.info("reconcile roles for dc={}", dataCenter.getMetadata().getName());
                for(CqlRole role : get(dataCenter).values()) {
                    if (!role.isApplied()) {
                        try {
                            role.createOrUpdateRole(dataCenter, k8sResourceUtils, sessionSupplier).blockingGet();
                        } catch (Exception ex) {
                            logger.error("Cannot load password or apply for role=" + role.getUsername() + " in dc=" + dataCenter.getMetadata().getName() + ":" + ex.getMessage());
                        }
                    }
                }
            }
        });
    }

    public void markRolesAsUnapplied(final DataCenter dc) {
        logger.debug("Mark roles as non applied for datacenter {}", dc.getMetadata().getName());

        for(CqlRole role : get(dc).values()) {
            role.setApplied(false);
            logger.trace("Set applied to false for '{}'", role.username);
        }
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
                    logger.debug("Creating a new CQL connection for dc={}", dc.getMetadata().getName());
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
                            break;
                        } catch (AuthenticationException e) {
                            // authentication failed
                            logger.debug("Authentication to dc={} failed with role={} from secret={}",
                                    dc.getMetadata().getName(), role.username, (role.secretKey == null) ? null : role.secret(dc));
                            lastException = e;
                        } catch (ApiException e) {
                            // cannot load k8s secret
                            logger.warn("Cannot load secret in dc={} for role={} from secret={}",
                                    dc.getMetadata().getName(), role.username, (role.secretKey == null) ? null : role.secret(dc));
                            lastException = e;
                        } catch (StrapkopException e) {
                            // password contains illegal caracters
                            logger.warn("Bas password in dc={} for role={} from secret={}",
                                    dc.getMetadata().getName(), role.username, role.secret(dc));
                            lastException = e;
                        } catch(DriverException e) {
                            logger.warn("Driver exception:" + e.getMessage(), e);
                            lastException = e;
                        } catch(Exception e) {
                            logger.debug("Unexpected exception:" + e.getMessage(), e);
                            lastException = e;
                        }
                    }

                    if (connectedRole == null) {
                        // auth failed for all roles
                        List<String> r = roles.stream().map(CqlRole::getUsername).collect(Collectors.toList());
                        logger.warn("Cannot connect dc={} with roles={}", dc.getMetadata().getName(), r);
                        dc.getStatus().setCqlStatus(CqlStatus.ERRORED);
                        dc.getStatus().setCqlStatusMessage("Authentication failed with roles=" + r);
                        if (lastException != null) {
                            logger.warn("Authentication failed with roles=" + r + " error:" + lastException.getMessage(), lastException);
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
                                logger.error("Cannot CreateOrUpdate role '{}'", role, e);
                            }
                        }

                        // reconnect with strapkop
                        CqlRole strakopRole = get(dc, CqlRole.STRAPKOP_ROLE.username);
                        try {
                            Tuple2<Cluster, Session> strapkopConnection = connect(dc, Optional.of(strakopRole));
                            // connection with strapkop user succeeded
                            // we close the previous session to avoid non relevant authentication exception
                            currentSesssion.close();
                            return strapkopConnection;
                        } catch(Exception e) {
                            logger.error("Failed to reconnect with the operator role="+strakopRole+" :"+e.getMessage(), e);
                        }
                    }
                    return tuple;
                }
            });
    }

    private Tuple2<Cluster, Session> connect(final DataCenter dc, Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException {
        Cluster cluster = createClusterObject(dc, optionalCqlRole);
        Session session = cluster.connect();
        dc.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
        dc.getStatus().setCqlStatusMessage("Connected to cluster=[" + cluster.getClusterName() + "]" +
                ((optionalCqlRole.isPresent()) ? (" with role=[" + optionalCqlRole.get().username+"] secret=["+optionalCqlRole.get().secret(dc)+"]") : ""));
        logger.debug("Connected to dc=" + dc.getMetadata().getName() + ((optionalCqlRole.isPresent()) ? (" with role=" + optionalCqlRole.get().username+" secret="+optionalCqlRole.get().secret(dc)) : ""));
        return new Tuple2<>(cluster, session);
    }

    private Cluster createClusterObject(final DataCenter dc, final Optional<CqlRole> optionalCqlRole) throws StrapkopException, ApiException, SSLException {
        // check the number of available node to adapt the ConsistencyLevel otherwise creating a DC with more than 1 node isn't possible
        // because licence can't be checked (UnavailableException: Not enough replicas available for query at consistency LOCAL_QUORUM (2 required but only 1 alive))
        // TODO [ELE] is it really the right fix? Do we have to adapt the RF and update the RF when cluster scales up instead???
        final long bootstrapedNode = dc.getStatus().getElassandraNodeStatuses().values().stream().filter(s -> !s.equals(ElassandraNodeStatus.UNKNOWN)).count();

        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .withQueryOptions(new QueryOptions().setConsistencyLevel(bootstrapedNode <= 1 ? ConsistencyLevel.LOCAL_ONE : ConsistencyLevel.LOCAL_QUORUM))
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()));

        // add remote seeds to contact points to be able to adjust RF of system keyspace before starting the first local node.
        if (dc.getSpec().getRemoteSeeds() != null)
            for(String remoteSeed : dc.getSpec().getRemoteSeeds()) {
                logger.debug("Add remote seed={} for datacenter={}", remoteSeed, dc.getMetadata().getName());
                builder.addContactPoint(remoteSeed);
            }

        // add seeds from remote seeders.
        if (dc.getSpec().getRemoteSeeders() != null) {
            for(String remoteSeeder : dc.getSpec().getRemoteSeeders()) {
                try {
                    for(InetAddress addr : ElassandraOperatorSeedProvider.seederCall(remoteSeeder)) {
                        logger.debug("Add remote seed={} from seeder={} for datacenter={}",
                                addr.getHostAddress(), remoteSeeder, dc.getMetadata().getName());
                        builder.addContactPoint(addr.getHostAddress());
                    }
                } catch (Exception e) {
                    logger.error("Seeder error", e);
                }
            }
        }

        // contact local nodes is bootstrapped or first DC in the cluster
        boolean hasSeedBootstrapped = dc.getStatus().getRackStatuses().values().stream().anyMatch(s -> s.getJoinedReplicas() > 0);
        if (hasSeedBootstrapped ||
                ((dc.getSpec().getRemoteSeeds() == null || dc.getSpec().getRemoteSeeds().isEmpty()) && (dc.getSpec().getRemoteSeeders() == null || dc.getSpec().getRemoteSeeders().isEmpty()))) {
            try {
                logger.debug("seed={} for datacenter={}", OperatorNames.nodesService(dc), dc.getMetadata().getName());
                builder.addContactPoint(OperatorNames.nodesService(dc));
            } catch(IllegalArgumentException e) {
                if (e.getCause() != null && e.getCause() instanceof  java.net.UnknownHostException) {
                    // ignore DNS resolution failure because dc removed....
                    logger.debug("seed={} for datacenter={} can't be added due to UnknownHostException, DC removed", OperatorNames.nodesService(dc), dc.getMetadata().getName());
                } else {
                    throw e;
                }
            }
        }

        if (Objects.equals(dc.getSpec().getSsl(), Boolean.TRUE)) {
            builder.withSSL(getSSLOptions());
        }

        if (optionalCqlRole.isPresent()) {
            logger.debug("username={} password={}", optionalCqlRole.get().getUsername(), optionalCqlRole.get().getPassword());
            builder.withCredentials(
                    optionalCqlRole.get().getUsername(),
                    optionalCqlRole.get().getPassword()
            );
        }

        builder.withoutMetrics(); // disable metric collection
        return builder.build();
    }

    private SSLOptions getSSLOptions() throws StrapkopException, ApiException, SSLException {
        SslContext sslContext = SslContextBuilder
                .forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(new ByteArrayInputStream(authorityManager.loadPublicCaFromSecret().getBytes(StandardCharsets.UTF_8)))
                .build();

        return new RemoteEndpointAwareNettySSLOptions(sslContext);
    }
}
