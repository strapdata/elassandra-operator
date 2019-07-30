package com.strapdata.strapkop.sidecar.cassandra;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("cassandra_jmx")
public class CassandraConfiguration {
    String jmxServiceURL = "service:jmx:rmi:///jndi/rmi://"+System.getenv("POD_IP")+":7199/jmxrmi";
}
