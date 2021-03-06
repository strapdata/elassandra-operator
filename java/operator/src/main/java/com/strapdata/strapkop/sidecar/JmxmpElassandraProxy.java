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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cache.JMXConnectorCache;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import com.strapdata.strapkop.ssl.utils.X509CertificateAndPrivateKey;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.http.uri.UriTemplate;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.vavr.Tuple2;
import org.apache.cassandra.locator.EndpointSnitchInfoMBean;
import org.apache.cassandra.service.StorageServiceMBean;
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
import java.util.concurrent.Callable;
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
    SharedInformerFactory sharedInformerFactory;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    AuthorityManager authorityManager;

    private Single<JMXConnector> getMbeanServerConn(ElassandraPod pod) throws MalformedURLException {
        JMXConnector jmxConnector = jmxConnectorCache.get(pod);
        if (jmxConnector != null)
            return Single.just(jmxConnector);

        DataCenter dc = getDataCenter(pod);
        Integer jmxPort = 7199;
        if (dc != null && dc.getSpec() != null && dc.getSpec().getJvm() != null && dc.getSpec().getJvm().getJmxPort() != null)
            jmxPort = dc.getSpec().getJvm().getJmxPort();

        String jmxUrl = UriTemplate.of("service:jmx:jmxmp://{jmxHost}:{jmxPort}/").expand(ImmutableMap.of(
                "jmxHost", pod.getFqdn(),
                "jmxPort", jmxPort));

        final JMXServiceURL jmxServiceURL = new JMXServiceURL(jmxUrl);
        return loadPassword(dc, this.k8sResourceUtils, OperatorNames.clusterSecret(dc), DataCenterUpdateAction.KEY_JMX_PASSWORD)
                .flatMap(jmxPassword -> {
                    final Map<String, Object> env = new HashMap<>();
                    if (dc.getSpec().getCassandra().getSsl()) {
                        // see https://docs.oracle.com/cd/E19698-01/816-7609/6mdjrf873/index.html
                        Security.addProvider(new com.sun.security.sasl.Provider());
                        env.put("jmx.remote.profiles", "TLS SASL/PLAIN");
                        env.put("jmx.remote.sasl.callback.handler", new UserPasswordCallbackHandler("cassandra", jmxPassword));

                        SSLSocketFactory sslsocketfactory = getSSLContext(pod.getNamespace(), pod.getCluster()).getSocketFactory();
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

    public void invalidateClient(ElassandraPod pod, Throwable t) {
        if (t instanceof java.net.UnknownHostException) {
            // pod hostname not yet available in the k8s DNS.
            logger.debug("Invalidating JMXMP connection UnknownHostException pod={}", pod.id());
        } else {
            logger.debug("Invalidating JMXMP connection pod="+pod.id(), t);
        }
        JMXConnector jmxConnector = jmxConnectorCache.remove(pod);
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
            }catch (IOException e){
            }
        }
    }

    private SSLContext getSSLContext(String namespace, String clusterName) throws StrapkopException, ApiException, IOException, ExecutionException, InterruptedException, GeneralSecurityException {
        X509CertificateAndPrivateKey ca = authorityManager.get(namespace, clusterName);
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
        return sharedInformerFactory.getExistingSharedIndexInformer(DataCenter.class).getIndexer().getByKey(pod.getNamespace() + "/" + pod.dataCenterName());
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

    public Single<Tuple2<StorageServiceMBean,JMXConnector>> getMBeanProvider(ElassandraPod pod) throws MalformedURLException {
        return getMbeanServerConn(pod)
                .map(jmxConnector -> {
                    logger.debug("storageServiceMBeanProvider pod={}", pod);
                    MBeanServerConnection mBeanServerConnection = jmxConnector.getMBeanServerConnection();
                    StorageServiceMBean storageServiceMBean = JMX.newMBeanProxy(mBeanServerConnection, STORAGE_SERVICE_MBEAN_NAME, StorageServiceMBean.class);
                    logger.debug("storageServiceMBeanProvider storageServiceMBean={}", storageServiceMBean);
                    return new Tuple2<>(storageServiceMBean, jmxConnector);
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
                })
                .ignoreElement()
                .onErrorResumeNext(t -> {
                    if (t instanceof UnsupportedOperationException) {
                        logger.info("Cannot remove node={} cause={}, trying to decommission pod={}", hostId, t.getMessage(), pod);
                        return decomission(pod);
                    }
                    return Completable.error(t);
                });
    }

    public Completable removeDcNodes(ElassandraPod pod, String dcName) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .flatMap(storageServiceMBean -> endpointSnitchInfoMBean(pod).map(endpointSnitchInfoMBean -> new Tuple2<>(storageServiceMBean, endpointSnitchInfoMBean)))
                .flatMapCompletable(tuple -> Completable.create(emitter -> {
                    try {
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
                        emitter.onComplete();
                    } catch(Throwable t) {
                        logger.error("error:", t);
                        emitter.onError(t);
                    }
                }));
    }

    public Completable drain(ElassandraPod pod) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    storageServiceMBean.drain();
                    logger.info("drain pod={}", pod.id());
                    return storageServiceMBean;
                }).ignoreElement();
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

    public void repairAsync(final StorageServiceMBean storageServiceMBean, JMXConnector jmxc, final String keyspace, Map<String, String> options) throws IOException
    {
        RepairRunner runner = new RepairRunner(storageServiceMBean, keyspace, options);
        try
        {
            jmxc.addConnectionNotificationListener(runner, null, null);
            storageServiceMBean.addNotificationListener(runner, null, null);
            runner.run();
        }
        catch (Exception e)
        {
            throw new IOException(e) ;
        }
        finally
        {
            try
            {
                storageServiceMBean.removeNotificationListener(runner);
                jmxc.removeConnectionNotificationListener(runner);
            }
            catch (Throwable e)
            {
                logger.error("Exception occurred during clean-up", e);
            }
        }
    }

    public Completable repairAsync(ElassandraPod pod, String keyspace) throws MalformedURLException {
        return storageServiceMBeanProvider(pod)
                .map(storageServiceMBean -> {
                    Map<String, String> options = new HashMap<>();
                    options.put("parallelism", "sequential");
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

    // sequential synchronous repair
    public Completable repair(ElassandraPod pod, String keyspace) throws MalformedURLException {
        return getMBeanProvider(pod)
                .flatMapCompletable(tuple -> {
                    Map<String, String> options = new HashMap<>();
                    options.put("parallelism", "sequential");
                    options.put("incremental", Boolean.FALSE.toString());
                    options.put("primaryRange", Boolean.TRUE.toString());
                    final List<String> keyspaces = keyspace == null ? tuple._1.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
                    Completable todo = Completable.complete();
                    for (String ks : keyspaces) {
                        todo = todo.andThen(Completable.fromCallable(new Callable<Object>() {
                                   @Override
                                    public Object call() throws Exception {
                                        repairAsync(tuple._1, tuple._2, keyspace, options);
                                        return null;
                                    }
                                }));
                        logger.info("Repair requested for keyspace={} pod={}", ks, pod.id());
                    }
                    return todo;
                });
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
