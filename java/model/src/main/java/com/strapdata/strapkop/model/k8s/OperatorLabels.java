package com.strapdata.strapkop.model.k8s;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;

import java.util.Map;

// should be called OperatorLabelsAndAnnotations...
public final class OperatorLabels {
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    public static final String ZONE = "failure-domain.beta.kubernetes.io/zone";
    public static final String REGION = "failure-domain.beta.kubernetes.io/region";
    public static final String INSTANCE_TYPE = "beta.kubernetes.io/instance-type";
    public static final String APP = "app";


    // public static final String labelPrefix = "elassandra-operator.strapdata.com/";
    // no prefix for compatibility with vroyer's grafana dashboard
    public static final String labelPrefix = "";

    
    public static final String PARENT = labelPrefix + "parent"; // parent datacenter resource name
    public static final String CLUSTER = labelPrefix + "cluster";
    public static final String DATACENTER = labelPrefix + "datacenter";
    public static final String RACK = labelPrefix + "rack";
    
    // TODO: this is confusing with helm "release" label
    public static final String RELEASE = labelPrefix + "release";
    
    public static final String DATACENTER_GENERATION = labelPrefix + "datacenter-generation";
    public static final String DATACENTER_FINGERPRINT = labelPrefix + "datacenter-fingerprint";

    // this annotation is used to store a hash of the config map in the pod so that statefulset trigger a rolling restart
    // when the config map change
    public static final String CONFIGMAP_FINGERPRINT = labelPrefix + "configmap-fingerprint";
    
    public static final Map<String, String> MANAGED = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator"
    );

    public static final Map<String, String> ELASSANDRA_PODS_SELECTOR = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator",
            "app", "elassandra"
    );

    private OperatorLabels() {}
    
    public static Map<String, String> cluster(String clusterName) {
        return ImmutableMap.<String, String>builder()
                .put(CLUSTER, clusterName)
                .putAll(MANAGED)
                .put(APP, "elassandra") // for grafana
                .build();
    }
    
    public static Map<String, String> datacenter(String parent, String clusterName, String dcName) {
        return ImmutableMap.<String, String>builder()
                .putAll(OperatorLabels.cluster(clusterName))
                .put(PARENT, parent)
                .put(DATACENTER, dcName)
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

    /*
    private static String extractTagFromImage(String imageName) {
        int pos = imageName.indexOf(":");
        if (pos == -1) {
            return "latest";
        }
        return imageName.substring(pos + 1);
    }
    */

    
    public static String toSelector(Map<String, String> labels) {
        return Joiner.on(',').withKeyValueSeparator('=').join(labels);
    }
}