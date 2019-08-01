package com.strapdata.strapkop.k8s;

import com.strapdata.model.k8s.cassandra.DataCenter;

public class OperatorNames {
    
    public static String clusterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
    }
    
    public static String dataCenterResource(final String clusterName, final String datacenterName) {
        return "elassandra-" + clusterName + "-" + datacenterName;
    }
    
    public static String dataCenterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat,
                dataCenterResource(dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName()));
    }
    
    public static String rackChildObjectName(final String nameFormat, final DataCenter dataCenter, final String rack) {
        return String.format(nameFormat,
                "elassandra-" + dataCenter.getSpec().getClusterName()
                        + "-" + dataCenter.getSpec().getDatacenterName()
                        + "-" + rack);
    }
    
    public static String clusterSecret(final DataCenter dataCenter) {
        return OperatorNames.clusterChildObjectName("%s", dataCenter);
    }
    
    public static String keystore(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-keystore", dataCenter);
    }
    
    public static String nodesService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s", dataCenter);
    }
    
    public static String elasticsearchService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-elasticsearch", dataCenter);
    }
    
    public static String seedsService(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }
    
    public static String prometheusServiceMonitor(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s", dataCenter);
    }
    
    public static String varConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-operator-var-config", dataCenter);
    }

    public static String specConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-operator-spec-config", dataCenter);
    }
    
    public static String rackConfig(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s-operator-config", dataCenter, rack);
    }
    
    public static String stsName(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s", dataCenter, rack);
    }
    
    public static String podName(final DataCenter dataCenter, final String rack, int podIndex) {
        return OperatorNames.rackChildObjectName("%s-" + podIndex, dataCenter, rack);
    }
    
    public static String podName(final DataCenter dataCenter, int crossRackPodIndex) {
        final String rack = "rack" + (crossRackPodIndex % dataCenter.getSpec().getRacks() + 1);
        final int podIndexInRack = crossRackPodIndex / dataCenter.getSpec().getRacks();
        return podName(dataCenter, rack, podIndexInRack);
    }
    
    public static String podFqdn(final DataCenter dc, final String podName) {
        return String.format("%s.%s.%s.svc.cluster.local", podName,
                OperatorNames.nodesService(dc), dc.getMetadata().getNamespace());
    }
}
