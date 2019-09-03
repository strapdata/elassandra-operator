package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.RemoteEndpointAwareNettySSLOptions;
import com.datastax.driver.core.SSLOptions;
import com.datastax.driver.core.Session;
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
 * Create and store cql connections
 */
@Singleton
public class CqlConnectionManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlConnectionManager.class);
    
    private final AuthorityManager authorityManager;
    private final CqlConnectionCache cache;
    
    public CqlConnectionManager(AuthorityManager authorityManager, CqlConnectionCache cache) {
        this.authorityManager = authorityManager;
        this.cache = cache;
    }
    
    
    /**
     * CQL connection reconciliation : ensure there is always a cql session activated
     */
    public void reconcileConnection(final DataCenter dc, final CqlCredentialsManager cqlCredentialsManager) throws StrapkopException, ApiException, SSLException {
        
        // abort if dc is not running
        if (!Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.RUNNING)) {
            return ;
        }
        
        final Session session = getConnection(dc);
        
        if (session == null || session.isClosed()) {
    
            if (dc.getSpec().getAuthentication().equals(Authentication.NONE)) {
                updateConnection(dc, null);
            }
            // do nothing if the credentials status is unknown
            else if (Objects.equals(dc.getStatus().getCredentialsStatus().getUnknown(), false)) {
                final CqlCredentials cqlCredentials = cqlCredentialsManager.getCurrentCredentials(dc);
                updateConnection(dc, cqlCredentials);
            }
        }
    }
    
    /**
     * Close existing connection to the dc and create a new one that will be stored for later retrieval
     * @param dc the datacenter to connect  to
     * @param credentials (optional) credentials if auth is required
     * @return a cql session
     * @throws DriverException
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    public Session updateConnection(final DataCenter dc, final CqlCredentials credentials) throws DriverException, StrapkopException, ApiException, SSLException {
        
        try {
            final Key key = new Key(dc.getMetadata());
    
            removeConnection(dc);
    
            logger.info("creating a new CQL connection for {}", dc.getMetadata().getName());
            final Cluster cluster = createClusterObject(dc, credentials);
            final Session session = cluster.connect();
            
            dc.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
            dc.getStatus().setCqlErrorMessage("");
            cache.put(key, Tuple.of(cluster, session));
            return session;
    
        }
        catch (DriverException exception) {
            dc.getStatus().getCredentialsStatus().setUnknown(true);
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
    
    public void removeConnection(final DataCenter dc) {
        final Key key = new Key(dc.getMetadata());
    
        final Tuple2<Cluster, Session> existing = cache.remove(key);
    
        if (existing != null && !existing._1.isClosed()) {
            logger.info("closing existing CQL connection for {}", dc.getMetadata().getName());
            existing._1.close();
        }
    
        dc.getStatus().setCqlStatus(CqlStatus.NOT_STARTED);
    }
    
    private Cluster createClusterObject(final DataCenter dc, final CqlCredentials credentials) throws StrapkopException, ApiException, SSLException {
        
        // TODO: updateConnection remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .addContactPoint(OperatorNames.nodesService(dc))
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()));
        
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
