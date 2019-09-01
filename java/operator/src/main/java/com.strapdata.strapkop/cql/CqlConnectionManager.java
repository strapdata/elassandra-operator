package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.RemoteEndpointAwareNettySSLOptions;
import com.datastax.driver.core.SSLOptions;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DCAwareRoundRobinPolicy;
import com.datastax.driver.core.policies.TokenAwarePolicy;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.cache.CqlConnectionCache;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Create and store cql connections and manager super user roles (cassandra, admin, strapkop)
 */
@Singleton
public class CqlConnectionManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlConnectionManager.class);

    private final AuthorityManager authorityManager;
    private final CqlConnectionCache cache;
    private final CoreV1Api coreApi;

    public CqlConnectionManager(AuthorityManager authorityManager, CqlConnectionCache cache, final CoreV1Api coreApi) {
        this.authorityManager = authorityManager;
        this.cache = cache;
        this.coreApi = coreApi;
    }

    /**
     * CQL connection reconciliation : ensure there is always a cql session activated
     */
    public void reconcileConnection(final DataCenter dc) throws StrapkopException, ApiException, SSLException {
        
        // abort if dc is not running
        if (!Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING)) {
            return ;
        }
        
        final Session session = getConnection(dc);
        if (session == null || session.isClosed()) {
            updateConnection(dc);
        }
    }

    /**
     * Close existing connection to the dc and create a new one that will be stored for later retrieval
     * @param dc the datacenter to connect  to
     * @return a cql session
     * @throws DriverException
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    public Session updateConnection(final DataCenter dc) throws DriverException, StrapkopException, ApiException, SSLException {
        try {
            final Key key = new Key(dc.getMetadata());
    
            removeConnection(dc);
    
            logger.info("creating a new CQL connection for {}", dc.getMetadata().getName());
            Cluster cluster;
            Session session;
            CqlRole role = null;

            if (!dc.getSpec().getAuthentication().equals(Authentication.NONE)) {
                role = CqlRole.STRAPKOP_ROLE;
                if (role.password == null)
                    // load the password from k8s secret
                    role.loadPassword(CqlRoleManager.getSecret(dc, this.coreApi));
            }

            try {
                cluster = createClusterObject(dc, role);
                session = cluster.connect();
                logger.debug("Connected with the role={} to cluster={} dc={}", role, dc.getSpec().getClusterName(), dc.getMetadata().getName());
            } catch(AuthenticationException e) {
                // retry with default cassandra user and password
                cluster = createClusterObject(dc, CqlRole.DEFAULT_CASSANDRA_ROLE);
                session = cluster.connect();
                logger.debug("Connected with the default cassandra role to dc={}", CqlRole.DEFAULT_CASSANDRA_ROLE.getUsername(), dc.getMetadata().getName());
            }
            dc.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
            dc.getStatus().setCqlErrorMessage("");
            cache.put(key, Tuple.of(cluster, session));
            return session;
        }
        catch (DriverException exception) {
            dc.getStatus().setCqlStatus(CqlStatus.ERRORED);
            dc.getStatus().setCqlErrorMessage(exception.getMessage());
            throw exception;
        }
    }
    
    public Session getConnection(final Key dcKey) {
        final Tuple2<Cluster, Session> t = cache.get(dcKey);
        if (t == null) {
            return null;
        }
        return t._2;
    }
    
    public Session getConnection(final DataCenter dc) {
        return getConnection(new Key(dc.getMetadata()));
    }

    public Session getSessionRequireNonNull(final DataCenter dc) throws StrapkopException {
        final Session session = getConnection(dc);
        if (session == null) {
            throw new StrapkopException("no cql connection available to initialize reaper keyspace");
        }
        return session;
    }

    public void removeConnection(final DataCenter dc) {
        final Key key = new Key(dc.getMetadata());
    
        final Tuple2<Cluster, Session> existing = cache.remove(key);
    
        if (existing != null && !existing._1.isClosed()) {
            logger.info("closing existing CQL connection for {}", dc.getMetadata().getName());
            existing._1.close();
        }
    
        dc.getStatus().setCqlStatus(CqlStatus.NOT_STARTED);
    }
    
    private Cluster createClusterObject(final DataCenter dc, final CqlRole credentials) throws StrapkopException, ApiException, SSLException {
        
        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .addContactPoint(OperatorNames.nodesService(dc))
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()));

        // add remote seeds to contact points to be able to adjust RF of system keyspace before starting the first local node.
        if (dc.getSpec().getRemoteSeeds() != null)
            for(String remoteSeed : dc.getSpec().getRemoteSeeds())
                builder.addContactPoint(remoteSeed);

        if (Objects.equals(dc.getSpec().getSsl(), Boolean.TRUE)) {
            builder.withSSL(getSSLOptions());
        }
        
        if (!Objects.equals(dc.getSpec().getAuthentication(), Authentication.NONE)) {
            
            Objects.requireNonNull(credentials);
            
            builder.withCredentials(
                    credentials.getUsername(),
                    credentials.getPassword()
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
    
    @PreDestroy
    @Override
    public void close() {
        logger.info("closing all opened cql connections");
        for (Map.Entry<Key, Tuple2<Cluster, Session>> entry : cache.entrySet()) {
            Cluster cluster = entry.getValue()._1;
            if (!cluster.isClosed()) {
                try {
                    cluster.close();
                }
                catch (DriverException e) {
                    logger.warn("error while closing cql connections", e);
                }
            }
        }
    }
}
