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

import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.openapi.ApiException;
import lombok.SneakyThrows;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutionException;

@Singleton
public class ElasticClientFactory {

    static final Logger logger = LoggerFactory.getLogger(ElasticClientFactory.class);

    private final AuthorityManager authorityManager;

    public ElasticClientFactory(AuthorityManager authorityManager, CqlRoleManager cqlRoleManager) {
        this.authorityManager = authorityManager;
    }

    /**
     * Get an elasticsearch client from cache or create it
     */
    public synchronized ElasticClient clientForPod(final ElassandraPod pod, CqlRole cqlRole) throws MalformedURLException, InterruptedException, ExecutionException, ApiException, SSLException {
        return new ElasticClient(new RestHighLevelClient(
                RestClient.builder(new HttpHost(pod.getFqdn(), pod.getEsPort(), pod.isSsl() ? "https" : "http"))
                        .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                            @SneakyThrows
                            @Override
                            public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                                if (cqlRole != null) {
                                    final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                                    credentialsProvider.setCredentials(AuthScope.ANY,
                                            new UsernamePasswordCredentials(cqlRole.getUsername(), cqlRole.getPassword()));
                                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                                }

                                if (pod.isSsl()) {
                                    httpClientBuilder.setSSLContext(getSSLContext(pod.getNamespace(), pod.getCluster()));
                                    // TODO: fix this workaround
                                    httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                                }

                                return httpClientBuilder;
                            }
                        })));
    }

    private SSLContext getSSLContext(String namespace, String clusterName) throws GeneralSecurityException, ExecutionException, InterruptedException, IOException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace, clusterName);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { ca.getX509TrustManager() }, null);
        return sslContext;
    }
}
