package com.strapdata.strapkop.sidecar;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.SidecarConnectionCache;
import com.strapdata.strapkop.cql.AbstractManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

/**
 * This is a sidecar client factory that caches client and reuse it as possible.
 *
 * The periodic node status checker should invalidate cache entry that are not working.
 * Java DNS caching has been disabled. If a pod is restarted and its IP change,
 * the next nodeStatus check would invalidate the cache (calling invalidateClient()), and the next call to the factory would recreate the client.
 */
@Singleton
public class SidecarClientFactory {

    static final Logger logger = LoggerFactory.getLogger(SidecarClientFactory.class);

    private final SidecarConnectionCache sidecarConnectionCache;
    private final AuthorityManager authorityManager;
    private final CqlRoleManager cqlRoleManager;

    public SidecarClientFactory(SidecarConnectionCache sidecarConnectionCache, AuthorityManager authorityManager, CqlRoleManager cqlRoleManager) {
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.authorityManager = authorityManager;
        this.cqlRoleManager = cqlRoleManager;
    }

    /**
     * Get a sidecar client from cache or create it
     */
    public synchronized SidecarClient clientForPod(final ElassandraPod pod, CqlRole cqlRole) throws MalformedURLException, InterruptedException, ExecutionException, ApiException, SSLException {
        SidecarClient sidecarClient = sidecarConnectionCache.get(pod);

        if (sidecarClient != null && sidecarClient.isRunning()) {
            logger.debug("hitting sidecar client cache for pod={}", pod.getName());
            return sidecarClient;
        }

        URL url = pod.isSsl() ? new URL("https://" + pod.getFqdn() + ":" + pod.getEsPort()) : new URL("http://" + pod.getFqdn() + ":" + pod.getEsPort());
        logger.debug("creating sidecar for pod={} in {}/{} url={}", pod.getName(), pod.getDataCenter(), pod.getNamespace(), url.toString());
        DefaultHttpClientConfiguration httpClientConfiguration = new DefaultHttpClientConfiguration();
        httpClientConfiguration.setReadTimeout(Duration.ofSeconds(30));
        sidecarClient = new SidecarClient(url, httpClientConfiguration, getSSLContext(pod.getNamespace()), cqlRoleManager, AbstractManager.key(pod.getNamespace(),
                pod.getCluster(),
                AbstractManager.key(pod.getNamespace(), pod.getCluster(), pod.getDataCenter())),
                cqlRole);
        sidecarConnectionCache.put(pod, sidecarClient);
        return sidecarClient;
    }

    private SslContext getSSLContext(String namespace) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace);
        return SslContextBuilder
                .forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(new ByteArrayInputStream(ca.getCertificateChainAsString().getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    /**
     * Remove and close a sidecar client from cache
     */
    public void invalidateClient(ElassandraPod pod, Throwable throwable) {
        logger.debug("invalidating cached sidecar client for pod="+pod.getName(), throwable);

        final SidecarClient sidecarClient = sidecarConnectionCache.remove(pod);

        if (sidecarClient != null) {
            sidecarClient.close();
        }
    }
}
