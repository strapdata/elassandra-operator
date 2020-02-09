package com.strapdata.strapkop.k8s;

public interface ServicesConstants {
    String CQL_PORT_NAME = "cql";
    String STORAGE_PORT_NAME = "internode";
    String ELASTICSEARCH_PORT_NAME = "elasticsearch";
    String ELASTICSEARCH_TRANSPORT_PORT_NAME = "transport";

    String PROMETHEUS_PORT_NAME = "prometheus";
    int PROMETHEUS_PORT_VALUE = 9500;
}
