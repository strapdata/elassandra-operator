package com.strapdata.strapkop.k8s;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;

import java.util.Map;

public final class OperatorMetadata {
    public static final String CLUSTER = "elassandra-operator.strapdata.com/cluster";
    public static final String DATACENTER = "elassandra-operator.strapdata.com/datacenter";
    public static final String RACK = "elassandra-operator.strapdata.com/rack";
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    
    // this is an annotation attached to datacenter child object that store a fingerprint of the datacenter spec
    public static final String DATACENTER_FINGERPRINT = "elassandra-operator.strapdata.com/datacenter-fingerprint";
    
    public static final Map<String, String> MANAGED = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator"
    );

    private OperatorMetadata() {}
    
    public static Map<String, String> datacenter(String clusterName, String dcName) {
        return ImmutableMap.<String, String>builder()
                .put(CLUSTER, clusterName)
                .put(DATACENTER, dcName)
                .putAll(MANAGED)
                .build();
    }
    
    public static Map<String, String> datacenter(DataCenter dataCenter) {
        return datacenter(dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName());
    }
    
    public static Map<String, String> rack(DataCenter dataCenter, String rackName) {
        return ImmutableMap.<String, String>builder()
                .putAll(datacenter(dataCenter))
                .put(RACK,rackName)
                .build();
    }
    
    public static Map<String, String> pod(DataCenter dataCenter, String rackName, String podName) {
        return ImmutableMap.<String, String>builder()
                .putAll(rack(dataCenter, rackName))
                .put(POD, podName)
                .build();
    }
    
    public static String toSelector(Map<String, String> labels) {
        return Joiner.on(',').withKeyValueSeparator('=').join(labels);
    }
}