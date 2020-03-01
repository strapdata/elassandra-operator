package com.strapdata.strapkop.sidecar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.JMXConnectorCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.http.uri.UriTemplate;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.vavr.Tuple2;
import jmx.org.apache.cassandra.locator.EndpointSnitchInfoMBean;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Infrastructure
public class JmxmpElassandraProxy {
    private static final Logger logger = LoggerFactory.getLogger(JmxmpElassandraProxy.class);

    public static final ObjectName GOSSIPER_MBEAN_NAME = ObjectNames.create("org.apache.cassandra.net:type=Gossiper");
    public static final ObjectName FAILURE_DETECTOR_MBEAN_NAME = ObjectNames.create("org.apache.cassandra.net:type=FailureDetector");
    public static final ObjectName ENDPOINT_SNITCH_INFO_MBEAN_NAME = ObjectNames.create("org.apache.cassandra.db:type=EndpointSnitchInfo");
    public static final ObjectName STORAGE_SERVICE_MBEAN_NAME = ObjectNames.create("org.apache.cassandra.db:type=StorageService");
    public static final ObjectName ELASTIC_NODE_METRICS_MBEAN_NAME = ObjectNames.create("org.elasticsearch.node:type=node");

    @Inject
    JMXConnectorCache jmxConnectorCache;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    AuthorityManager authorityManager;

    private Single<JMXConnector> getMbeanServerConn(ElassandraPod pod) throws MalformedURLException {
        DataCenter dc = getDataCenter(pod);
        String jmxUrl = UriTemplate.of("service:jmx:jmxmp://{jmxHost}:{jmxPort}/").expand(ImmutableMap.of(
                "jmxHost", pod.getFqdn(),
                "jmxPort", dc.getSpec().getJmxPort()));
        JMXConnector jmxConnector = null; //jmxConnectorCache.get(pod);
        if (jmxConnector != null)
            return Single.just(jmxConnector);

        final JMXServiceURL jmxServiceURL = new JMXServiceURL(jmxUrl);
        return loadPassword(dc, this.k8sResourceUtils, OperatorNames.clusterSecret(dc), DataCenterUpdateAction.KEY_JMX_PASSWORD)
                .flatMap(jmxPassword -> {
                    final Map<String, Object> env = new HashMap<>();
                    if (dc.getSpec().getSsl()) {
                        // see https://docs.oracle.com/cd/E19698-01/816-7609/6mdjrf873/index.html
                        Security.addProvider(new com.sun.security.sasl.Provider());
                        env.put("jmx.remote.profiles", "TLS SASL/PLAIN");
                        env.put("jmx.remote.sasl.callback.handler", new UserPasswordCallbackHandler("cassandra", jmxPassword));

                        SSLSocketFactory sslsocketfactory = getSSLContext(pod.getNamespace()).getSocketFactory();
                        env.put("jmx.remote.tls.socket.factory", sslsocketfactory);
                        logger.trace("JMXMP over SSL, ciphersuites={}", Arrays.asList(sslsocketfactory.getDefaultCipherSuites()));
                    }

                    return Single.create(emitter -> {
                        try {
                            logger.debug("New JMXConnector url={}", jmxServiceURL);
                            JMXConnector jmxConnector1 = JMXConnectorFactory.newJMXConnector(jmxServiceURL, env);
                            jmxConnector1.connect();
                            logger.trace("jmxConnector={}", jmxConnector1);
                            emitter.onSuccess(jmxConnector1);
                        } catch (Throwable t) {
                            emitter.onError(t);
                        }
                    });
                })
                .map(jc -> (JMXConnector) jc)
                .map(jc -> {
                    jmxConnectorCache.put(pod, jc);
                    return jc;
                });
    }

    public void invalidateClient(ElassandraPod pod, Throwable t) throws IOException {
        if (t instanceof java.net.UnknownHostException) {
            // pod hostname not yet available in the k8s DNS.
            logger.debug("Invalidating JMXMP connection UnknownHostException pod={}", pod.id());
        } else {
            logger.warn("Invalidating JMXMP connection pod="+pod.id(), t);
        }
        jmxConnectorCache.remove(pod).close();
    }

    private SSLContext getSSLContext(String namespace) throws StrapkopException, ApiException, IOException, ExecutionException, InterruptedException, GeneralSecurityException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { new JmxmpTrustManager(ca.getCertificate()) }, new SecureRandom());
        return context;
    }

    static class JmxmpTrustManager implements X509TrustManager {
        X509Certificate[] trustedCa;
        public JmxmpTrustManager(X509Certificate ca) {
            this.trustedCa = new X509Certificate[] { ca };
        }
        public void checkClientTrusted(X509Certificate[] arg0, String arg1){}
        public void checkServerTrusted(X509Certificate[] arg0, String arg1){}
        public X509Certificate[] getAcceptedIssuers() {
            return this.trustedCa;
        }
    }

    DataCenter getDataCenter(ElassandraPod pod) {
        return this.dataCenterCache.get(new Key(pod.dataCenterName(), pod.getNamespace()));
    }

    Single<String> loadPassword(DataCenter dataCenter, K8sResourceUtils k8sResourceUtils, String secretName, String secretKey) {
        return k8sResourceUtils.readNamespacedSecret(dataCenter.getMetadata().getNamespace(), secretName)
                .map(secret -> {
                    byte[] passBytes = secret.getData().get(secretKey);
                    if (passBytes == null) {
                        logger.error("secret={} does not contain key={}", secretName, secretKey);
                        throw new StrapkopException("secret=" + secret.getMetadata().getName() + " does not contain key=" + secretKey);
                    }
                    return new String(passBytes);
                });
    }

    public Single<StorageServiceMBean> storageServiceMBeanProvider(ElassandraPod pod) throws MalformedURLException {
        return getMbeanServerConn(pod)
                .map(jmxConnector -> {
                    logger.debug("storageServiceMBeanProvider pod={}", pod);
                    MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
                    StorageServiceMBean storageServiceMBean = JMX.newMBeanProxy(mBeanServerConnection, STORAGE_SERVICE_MBEAN_NAME, StorageServiceMBean.class);
                    logger.debug("storageServiceMBeanProvider storageServiceMBean={}", storageServiceMBean);
                    return storageServiceMBean;
                });
    }

    public Single<EndpointSnitchInfoMBean> endpointSnitchInfoMBean(ElassandraPod pod) throws MalformedURLException {
        return getMbeanServerConn(pod)
                .map(jmxConnector -> {
                    MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
                    return JMX.newMBeanProxy(mBeanServerConnection, ENDPOINT_SNITCH_INFO_MBEAN_NAME, EndpointSnitchInfoMBean.class);
                });
    }

    public Single<ElasticNodeMetricsMBean> elasticNodeMetricsMBeanProvider(ElassandraPod pod) throws MalformedURLException {
        return getMbeanServerConn(pod)
                .map(jmxConnector -> {
                    MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
                    return JMX.newMBeanProxy(mBeanServerConnection, ELASTIC_NODE_METRICS_MBEAN_NAME, ElasticNodeMetricsMBean.class);
                });
    }

    static class UserPasswordCallbackHandler implements javax.security.auth.callback.CallbackHandler {
        private String username;
        private char[] password;

        public UserPasswordCallbackHandler(String user, String password) {
            this.username = user;
            this.password = password == null ? null : password.toCharArray();
        }

        public void handle(javax.security.auth.callback.Callback[] callbacks)
                throws IOException, javax.security.auth.callback.UnsupportedCallbackException {
            for (int i = 0; i < callbacks.length; i++) {
                if (callbacks[i] instanceof PasswordCallback) {
                    PasswordCallback pcb = (PasswordCallback) callbacks[i];
                    pcb.setPassword(password);
                } else if (callbacks[i] instanceof javax.security.auth.callback.NameCallback) {
                    NameCallback ncb = (NameCallback) callbacks[i];
                    ncb.setName(username);
                } else {
                    throw new UnsupportedCallbackException(callbacks[i]);
                }
            }
        }

        private void clearPassword() {
            if (password != null) {
                for (int i = 0; i < password.length; i++)
                    password[i] = 0;
                password = null;
            }
        }

        protected void finalize() {
            clearPassword();
        }
    }

    public Single<ElassandraNodeStatus> status(ElassandraPod pod) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    logger.debug("status pod={}", pod);
                    ElassandraNodeStatus elassandraNodeStatus = ElassandraNodeStatus.valueOf(storageServiceMBean.getOperationMode());
                    logger.debug("elassandraNodeStatus={} pod={}", elassandraNodeStatus, pod.id());
                    return elassandraNodeStatus;
                });
    }

    public Completable flush(ElassandraPod pod, String keyspace) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
                    for (String ks : keyspaces) {
                        storageServiceMBean.forceKeyspaceFlush(ks);
                        logger.info("Flush done for keyspace={} pod={}", ks, pod.id());
                    }
                    return storageServiceMBean;
                }).ignoreElement();
    }

    public Completable removeNode(ElassandraPod pod, String hostId) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    storageServiceMBean.removeNode(hostId);
                    logger.info("node removed pod={}", pod.id());
                    return storageServiceMBean;
                }).ignoreElement();
    }

    public Completable removeDcNodes(ElassandraPod pod, String dcName) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .flatMap(storageServiceMBean -> endpointSnitchInfoMBean(pod).map(endpointSnitchInfoMBean -> new Tuple2<>(storageServiceMBean, endpointSnitchInfoMBean)))
                .flatMapCompletable(tuple -> Completable.create(emitter -> {
                    Map<String, String> endpointToHostId = tuple._1.getEndpointToHostId();
                    Set<String> endpoints = tuple._1.getTokenToEndpointMap().values().stream().collect(Collectors.toSet());
                    for (String endpoint : endpoints) {
                        String dc = tuple._2.getDatacenter(endpoint);
                        if (dcName.equals(dc)) {
                            String hostId = endpointToHostId.get(endpoint);
                            logger.debug("pod={} removing node endpoint={} id={}", pod.id(), endpoint, hostId);
                            tuple._1.removeNode(hostId);
                        }
                    }
                    logger.info("nodes of datacenter={} removed from pod={}", dcName, pod.id());
                }));
    }

    public Completable decomission(ElassandraPod pod) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    storageServiceMBean.decommission();
                    logger.info("decommission pod={}", pod.id());
                    return storageServiceMBean;
                }).ignoreElement();
    }

    public Completable cleanup(ElassandraPod pod, String keyspace) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
                    for (String ks : keyspaces) {
                        storageServiceMBean.forceKeyspaceCleanup(2, ks);
                        logger.info("Cleanup done for keyspace={} pod={}", ks, pod.id());
                    }
                    return storageServiceMBean;
                }).ignoreElement();
    }

    public Completable repair(ElassandraPod pod, String keyspace) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    Map<String, String> options = new HashMap<>();
                    options.put("incremental", Boolean.FALSE.toString());
                    options.put("primaryRange", Boolean.TRUE.toString());
                    final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
                    for (String ks : keyspaces) {
                        storageServiceMBean.repairAsync(ks, options);
                        logger.info("Repair requested for keyspace={} pod={}", ks, pod.id());
                    }
                    return storageServiceMBean;
                }).ignoreElement();
    }

    public Completable rebuild(ElassandraPod pod, String srcDcName, String keyspace) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    logger.debug("Rebuilding from dc={} requested for keyspace={} on pod={}", srcDcName, keyspace, pod.id());
                    storageServiceMBean.rebuild(srcDcName, keyspace, null, null);
                    logger.info("Rebuilt from dc={} requested for keyspace={} on pod={}", srcDcName, keyspace, pod.id());
                    return storageServiceMBean;
                }).ignoreElement();
    }
}
