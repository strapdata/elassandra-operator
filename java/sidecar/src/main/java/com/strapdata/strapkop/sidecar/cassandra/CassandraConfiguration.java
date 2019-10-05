package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("cassandra_jmx")
public class CassandraConfiguration {
    String jmxServiceURL = "service:jmx:rmi:///jndi/rmi://"+System.getenv("POD_IP")+":" + System.getenv("JMX_PORT") + "/jmxrmi";

    String jmxUsername = "cassandra";

    String jmxPassword = System.getenv("JMX_PASSWORD");
}
