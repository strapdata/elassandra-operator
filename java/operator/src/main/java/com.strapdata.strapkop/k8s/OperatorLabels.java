package com.strapdata.strapkop.k8s;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;

import java.util.Map;

// should be called OperatorLabelsAndAnnotations...
public final class OperatorLabels {
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    public static final String ZONE = "failure-domain.beta.kubernetes.io/zone";
    
    public static final String PARENT = "elassandra-operator.strapdata.com/parent"; // parent datacenter resource name
    public static final String CLUSTER = "elassandra-operator.strapdata.com/cluster";
    public static final String DATACENTER = "elassandra-operator.strapdata.com/datacenter";
    public static final String RACK = "elassandra-operator.strapdata.com/rack";
    
    public static final String DATACENTER_GENERATION = "elassandra-operator.strapdata.com/datacenter-generation";
    
    // this annotation is used to store a hash of the config map in the pod so that statefulset trigger a rolling restart
    // when the config map change
    public static final String CONFIGMAP_FINGERPRINT = "elassandra-operator.strapdata.com/configmap-fingerprint";
    
    public static final Map<String, String> MANAGED = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator"
    );

    private OperatorLabels() {}
    
    public static Map<String, String> cluster(String clusterName) {
        return ImmutableMap.<String, String>builder()
                .put(CLUSTER, clusterName)
                .putAll(MANAGED)
                .build();
    }
    
    public static Map<String, String> datacenter(String parent, String clusterName, String dcName) {
        return ImmutableMap.<String, String>builder()
                .put(PARENT, parent)
                .put(CLUSTER, clusterName)
                .put(DATACENTER, dcName)
                .putAll(MANAGED)
                .build();
    }
    
    public static Map<String, String> datacenter(DataCenter dataCenter) {
        return datacenter(dataCenter.getMetadata().getName(), dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName());
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