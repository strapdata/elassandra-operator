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
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cache.CqlConnectionCache;
import com.strapdata.strapkop.exception.StrapkopException;
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

@Singleton
public class CqlConnectionManager implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlConnectionManager.class);
    
    private final AuthorityManager authorityManager;
    private final CqlConnectionCache cache;
    
    public CqlConnectionManager(AuthorityManager authorityManager, CqlConnectionCache cache) {
        this.authorityManager = authorityManager;
        this.cache = cache;
    }
    
    public Session add(final DataCenter dc, final CqlCredentials credentials) throws DriverException, StrapkopException, ApiException, SSLException {
        final Cluster cluster = createClusterObject(dc, credentials);
        final Key key = new Key(dc.getMetadata());
    
        logger.info("creating a new CQL connection for {}", dc.getMetadata().getName());
        final Session session = cluster.connect();
        
        final Tuple2<Cluster, Session> existing = cache.get(key);
        if (existing != null && !existing._1.isClosed()) {
            logger.info("closing existing CQL connection for {}", dc.getMetadata().getName());
            existing._1.close();
        }
        
        cache.insert(key, Tuple.of(cluster, session));
        return session;
    }
    
    public Session get(final Key dcKey) {
        final Tuple2<Cluster, Session> t = cache.get(dcKey);
        if (t == null) {
            return null;
        }
        return t._2;
    }
    
    public Session get(final DataCenter dc) {
        return get(new Key(dc.getMetadata()));
    }
    
    private Cluster createClusterObject(final DataCenter dc, final CqlCredentials credentials) throws StrapkopException, ApiException, SSLException {
        
        // TODO: add remote seeds as contact point
        final Cluster.Builder builder = Cluster.builder()
                .withClusterName(dc.getSpec().getClusterName())
                .withPort(dc.getSpec().getNativePort())
                .addContactPoint(String.format("elassandra-%s-%s",
                        dc.getSpec().getClusterName(),
                        dc.getSpec().getDatacenterName()))
                .withLoadBalancingPolicy(new TokenAwarePolicy(
                        DCAwareRoundRobinPolicy.builder()
                                .withLocalDc(dc.getSpec().getDatacenterName())
                                .build()));
        
        if (Objects.equals(dc.getSpec().getSsl(), Boolean.TRUE)) {
            builder.withSSL(getSSLOptions());
        }
        
        if (Objects.equals(dc.getSpec().getAuthentication(), Authentication.CASSANDRA)) {
            
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
        for (Map.Entry<Key, Tuple2<Cluster, Session>> entry : cache.list()) {
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
