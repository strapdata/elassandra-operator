package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("cassandra_jmx")
public class CassandraConfiguration {
    String jmxServiceURL = "service:jmx:rmi:///jndi/rmi://localhost:7199/jmxrmi";
}
