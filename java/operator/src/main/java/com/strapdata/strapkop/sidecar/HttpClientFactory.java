/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.sidecar;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.HttpConnectionCache;
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
public class HttpClientFactory {

    static final Logger logger = LoggerFactory.getLogger(HttpClientFactory.class);

    private final HttpConnectionCache httpConnectionCache;
    private final AuthorityManager authorityManager;
    private final CqlRoleManager cqlRoleManager;

    public HttpClientFactory(HttpConnectionCache httpConnectionCache, AuthorityManager authorityManager, CqlRoleManager cqlRoleManager) {
        this.httpConnectionCache = httpConnectionCache;
        this.authorityManager = authorityManager;
        this.cqlRoleManager = cqlRoleManager;
    }

    /**
     * Get a sidecar client from cache or create it
     */
    public synchronized HttpClient clientForPod(final ElassandraPod pod, CqlRole cqlRole) throws MalformedURLException, InterruptedException, ExecutionException, ApiException, SSLException {
        HttpClient sidecarClient = httpConnectionCache.get(pod);

        if (sidecarClient != null && sidecarClient.isRunning()) {
            logger.debug("hitting sidecar client cache for pod={}", pod.getName());
            return sidecarClient;
        }

        URL url = pod.isSsl() ? new URL("https://" + pod.getFqdn() + ":" + pod.getEsPort()) : new URL("http://" + pod.getFqdn() + ":" + pod.getEsPort());
        logger.debug("creating sidecar for pod={} in {}/{} url={}", pod.getName(), pod.getDataCenter(), pod.getNamespace(), url.toString());
        DefaultHttpClientConfiguration httpClientConfiguration = new DefaultHttpClientConfiguration();
        httpClientConfiguration.setReadTimeout(Duration.ofSeconds(30));
        sidecarClient = new HttpClient(url, httpClientConfiguration, getSslContext(pod.getNamespace(), pod.getCluster()), cqlRole);
        httpConnectionCache.put(pod, sidecarClient);
        return sidecarClient;
    }

    private SslContext getSslContext(String namespace, String clusterName) throws StrapkopException, ApiException, SSLException, ExecutionException, InterruptedException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace, clusterName);
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

        final HttpClient sidecarClient = httpConnectionCache.remove(pod);

        if (sidecarClient != null) {
            sidecarClient.close();
        }
    }
}
