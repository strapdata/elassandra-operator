package com.strapdata.strapkop.model.k8s;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;

import java.util.Map;
import java.util.stream.Collectors;

// should be called OperatorLabelsAndAnnotations...
public final class OperatorLabels {

    //Note: Starting in v1.17, this label is deprecated in favor of topology.kubernetes.io/zone.
    public static final String ZONE = "failure-domain.beta.kubernetes.io/zone";
    public static final String REGION = "failure-domain.beta.kubernetes.io/region";

    public static final String TOPOLOGY_REGION = "topology.kubernetes.io/region";
    public static final String TOPOLOGY_ZONE = "topology.kubernetes.io/zone";

    public static final String INSTANCE_TYPE = "beta.kubernetes.io/instance-type";
    public static final String POD = "statefulset.kubernetes.io/pod-name";
    public static final String APP = "app";

    // public static final String labelPrefix = "elassandra-operator.strapdata.com/";
    // no prefix for compatibility with vroyer's grafana dashboard
    public static final String labelPrefix = "elassandra.strapdata.com/";


    public static final String PARENT = labelPrefix + "parent"; // parent datacenter resource name
    public static final String CLUSTER = labelPrefix + "cluster";
    public static final String DATACENTER = labelPrefix + "datacenter";
    public static final String RACK = labelPrefix + "rack";
    public static final String RACKINDEX = labelPrefix + "rackindex";

    public static final String JVM_OPTIONS = labelPrefix + "jvm.options";

    public static final String CREDENTIAL = labelPrefix + "credential";
    public static final String KEYSTORE = labelPrefix + "keystore";

    public static final String DATACENTER_GENERATION = labelPrefix + "datacenter-generation";
    public static final String DATACENTER_FINGERPRINT = labelPrefix + "datacenter-fingerprint";

    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    public static final String ELASSANDRA_APP = "elassandra";
    public static final String ELASSANDRA_OPERATOR = "elassandra-operator";

    public static final Map<String, String> MANAGED = ImmutableMap.of(MANAGED_BY, ELASSANDRA_OPERATOR);

    public static final Map<String, String> ELASSANDRA_PODS_SELECTOR = ImmutableMap.of(
            MANAGED_BY, ELASSANDRA_OPERATOR,
            "app", ELASSANDRA_APP
    );

    private OperatorLabels() {}

    public static Map<String, String> cluster(String clusterName) {
        return ImmutableMap.<String, String>builder()
                .put(CLUSTER, clusterName)
                .putAll(MANAGED)
                .put(APP, ELASSANDRA_APP) // for grafana
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

    public static Map<String, String> rack(DataCenter dataCenter, String rackName, int rackIndex) {
        return ImmutableMap.<String, String>builder()
                .putAll(datacenter(dataCenter))
                .put(RACK,rackName)
                .put(RACKINDEX, Integer.toString(rackIndex))
                .build();
    }

    public static Map<String, String> rackIndex(DataCenter dataCenter, int rackIndex) {
        return ImmutableMap.<String, String>builder()
                .putAll(datacenter(dataCenter))
                .put(RACKINDEX, Integer.toString(rackIndex))
                .build();
    }

    public static Map<String, String> pod(DataCenter dataCenter, String rackName, int rackIndex, String podName) {
        return ImmutableMap.<String, String>builder()
                .putAll(rack(dataCenter, rackName, rackIndex))
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
        return labels.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).collect(Collectors.joining(","));
    }
}