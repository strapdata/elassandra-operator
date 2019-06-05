package com.strapdata.strapkop.k8s;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

public final class OperatorLabels {
    public static final String DATACENTER = "elassandra-operator.strapdata.com/datacenter";
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    
    public static final Map<String, String> MANAGED = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator"
    );

    private OperatorLabels() {}
    
    public static Map<String, String> datacenter(String name) {
        return ImmutableMap.<String, String>builder()
                .put(DATACENTER, name)
                .putAll(MANAGED)
                .build();
    }
    
    public static Map<String, String> pod(String dcName, String podName) {
        return ImmutableMap.<String, String>builder()
                .putAll(datacenter(dcName))
                .put(POD, podName)
                .build();
    }
    
    public static String toSelector(Map<String, String> labels) {
        return Joiner.on(',').withKeyValueSeparator('=').join(labels);
    }
}