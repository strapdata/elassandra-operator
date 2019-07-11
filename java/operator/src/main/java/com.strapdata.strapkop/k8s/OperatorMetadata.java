package com.strapdata.strapkop.k8s;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

public final class OperatorMetadata {
    public static final String DATACENTER = "elassandra-operator.strapdata.com/datacenter";
    public static final String RACK = "elassandra-operator.strapdata.com/rack";
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    
    // this is an annotation attached to datacenter child object that store a fingerprint of the datacenter spec
    public static final String DATACENTER_FINGERPRINT = "elassandra-operator.strapdata.com/datacenter-fingerprint";
    
    public static final Map<String, String> MANAGED = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator"
    );

    private OperatorMetadata() {}
    
    public static Map<String, String> datacenter(String name) {
        return ImmutableMap.<String, String>builder()
                .put(DATACENTER, name)
                .putAll(MANAGED)
                .build();
    }
    
    public static Map<String, String> rack(String dcName, String rackName) {
        return ImmutableMap.<String, String>builder()
                .putAll(datacenter(dcName))
                .put(RACK,rackName)
                .build();
    }
    
    public static Map<String, String> pod(String dcName, String rackName, String podName) {
        return ImmutableMap.<String, String>builder()
                .putAll(rack(dcName, rackName))
                .put(POD, podName)
                .build();
    }
    
    public static String toSelector(Map<String, String> labels) {
        return Joiner.on(',').withKeyValueSeparator('=').join(labels);
    }
    
}