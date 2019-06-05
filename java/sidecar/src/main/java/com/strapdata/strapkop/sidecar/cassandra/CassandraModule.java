package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Infrastructure;
import jmx.org.apache.cassandra.service.StorageServiceMBean;

import javax.inject.Singleton;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;

@Infrastructure
@Factory
public class CassandraModule {
    private final MBeanServerConnection mBeanServerConnection;

    public CassandraModule(final CassandraConfiguration config) throws IOException {
        final JMXConnector connector = JMXConnectorFactory.connect( new JMXServiceURL(config.jmxServiceURL));
        final MBeanServerConnection mBeanServerConnection = connector.getMBeanServerConnection();
        this.mBeanServerConnection = mBeanServerConnection;
    }

    @Singleton
    public StorageServiceMBean storageServiceMBeanProvider() {
        return JMX.newMBeanProxy(mBeanServerConnection, CassandraObjectNames.STORAGE_SERVICE_MBEAN_NAME, StorageServiceMBean.class);
    }

    @Singleton
    public ElasticNodeMetricsMBean elasticNodeMetricsMBeanProvider() {
        return JMX.newMBeanProxy(mBeanServerConnection, CassandraObjectNames.ELASTIC_NODE_METRICS_MBEAN_NAME, ElasticNodeMetricsMBean.class);
    }
}
