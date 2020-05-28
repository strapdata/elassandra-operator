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
                                    httpClientBuilder.setSSLContext(getSSLContext(pod.getNamespace()));
                                    // TODO: fix this workaround
                                    httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
                                }

                                return httpClientBuilder;
                            }
                        })));
    }

    private SSLContext getSSLContext(String namespace) throws GeneralSecurityException, ExecutionException, InterruptedException, IOException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[] { ca.getX509TrustManager() }, null);
        return sslContext;
    }
}
