package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Infrastructure;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.io.IOException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.util.HashMap;
import java.util.Map;

@Infrastructure
@Factory
public class CassandraModule {
    private static final Logger logger = LoggerFactory.getLogger(CassandraModule.class);

    private final MBeanServerConnection mBeanServerConnection;

    public CassandraModule(final CassandraConfiguration config) throws IOException {
        Map<String, Object> env = new HashMap<>();
        env.put(JMXConnector.CREDENTIALS, new String[] { config.jmxUsername, config.jmxPassword });
        env.put("com.sun.jndi.rmi.factory.socket", getRmiClientSocketFactory());

        logger.debug("jmxServiceURL={} jmxUsername={} jmxPassword={}", config.jmxServiceURL, config.jmxUsername, config.jmxPassword);
        try {
            final JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(config.jmxServiceURL), env);
            this.mBeanServerConnection = connector.getMBeanServerConnection();
        } catch(Exception e) {
            logger.error("MBean connection error:", e);
            throw e;
        }
    }

    @Singleton
    public StorageServiceMBean storageServiceMBeanProvider() {
        return JMX.newMBeanProxy(mBeanServerConnection, CassandraObjectNames.STORAGE_SERVICE_MBEAN_NAME, StorageServiceMBean.class);
    }

    @Singleton
    public ElasticNodeMetricsMBean elasticNodeMetricsMBeanProvider() {
        return JMX.newMBeanProxy(mBeanServerConnection, CassandraObjectNames.ELASTIC_NODE_METRICS_MBEAN_NAME, ElasticNodeMetricsMBean.class);
    }

    private static RMIClientSocketFactory getRmiClientSocketFactory() {
        return Boolean.parseBoolean(System.getProperty("ssl.enable"))
                ? new SslRMIClientSocketFactory()
                : RMISocketFactory.getDefaultSocketFactory();
    }
}
