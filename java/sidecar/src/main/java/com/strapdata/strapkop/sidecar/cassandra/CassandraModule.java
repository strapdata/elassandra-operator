package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Infrastructure;
import jmx.org.apache.cassandra.locator.EndpointSnitchInfoMBean;
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
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import java.io.IOException;
import java.rmi.server.RMIClientSocketFactory;
import java.rmi.server.RMISocketFactory;
import java.security.Security;
import java.util.HashMap;
import java.util.Map;

@Infrastructure
@Factory
public class CassandraModule {
    private static final Logger logger = LoggerFactory.getLogger(CassandraModule.class);

    private final MBeanServerConnection mBeanServerConnection;

    @SuppressWarnings("unchecked")
    public CassandraModule(final CassandraConfiguration config) throws IOException {
        Map<String, Object> env = new HashMap<>();
        if (System.getProperty("cassandra.jmxmp") != null) {
            if (Boolean.parseBoolean(System.getProperty("ssl.enable"))) {
                Security.addProvider(new com.sun.security.sasl.Provider());
                env.put("jmx.remote.profiles", "TLS SASL/PLAIN");
                env.put("jmx.remote.sasl.callback.handler", new UserPasswordCallbackHandler(config.jmxUsername, config.jmxPassword));
            }
        } else {
            if (config.jmxUsername != null && config.jmxPassword != null)
                env.put(JMXConnector.CREDENTIALS, new String[] { config.jmxUsername, config.jmxPassword });
            env.put("com.sun.jndi.rmi.factory.socket", getRmiClientSocketFactory());
        }

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
    public EndpointSnitchInfoMBean endpointSnitchInfoMBean() {
        return JMX.newMBeanProxy(mBeanServerConnection, CassandraObjectNames.ENDPOINT_SNITCH_INFO_MBEAN_NAME, EndpointSnitchInfoMBean.class);
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
}
