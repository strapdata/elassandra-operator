package com.strapdata.strapkop.k8s;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1OwnerReference;

import java.util.UUID;

public class OperatorNames {

    public static String configMapUniqueName(final String name, final String fingerprint) {
        return name + "-" + fingerprint;
    }

    public static String historyDataCenterName(final String datacenterName, final long generation) {
        return String.format("%s-%04d", datacenterName, generation);
    }

    public static String clusterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
    }

    public static String dataCenterResource(final String clusterName, final String datacenterName) {
        return "elassandra-" + clusterName + "-" + datacenterName;
    }

    public static String rackResource(final String clusterName, final String datacenterName, final String rack) {
        return "elassandra-" + clusterName + "-" + datacenterName + "-" +rack;
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

    public static String clusterRcFilesSecret(final DataCenter dataCenter) {
        return OperatorNames.clusterChildObjectName("%s-rc", dataCenter);
    }

    public static String keystoreSecret(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-keystore", dataCenter);
    }

    public static String nodesService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s", dataCenter);
    }

    public static String elasticsearchService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-elasticsearch", dataCenter);
    }

    public static String externalService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-external", dataCenter);
    }

    public static String seedsService(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }

    public static String seedConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }

    public static String specConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-spec", dataCenter);
    }

    public static String rackConfig(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s", dataCenter, rack);
    }

    public static String stsName(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s", dataCenter, rack);
    }

    public static String podName(final DataCenter dataCenter, final String rack, int podIndex) {
        return OperatorNames.rackChildObjectName("%s-" + podIndex, dataCenter, rack);
    }

    public static String podFqdn(final DataCenter dc, final String podName) {
        return String.format("%s.%s.%s.svc.cluster.local", podName,
                OperatorNames.nodesService(dc), dc.getMetadata().getNamespace());
    }

    public static String podFqdn(final String namespace, final String clusterName, String dcName, final String podName) {
        return String.format("%s.%s.%s.svc.cluster.local", podName,
                dataCenterResource(clusterName, dcName), namespace);
    }

    
    public static String generateTaskName(DataCenter dc, String taskType) {
        return OperatorNames.dataCenterChildObjectName("%s-" + taskType + "-" + UUID.randomUUID().toString().substring(0, 8), dc);
    }

    public static V1OwnerReference ownerReference(DataCenter dataCenter) {
        return new V1OwnerReference()
                .kind(dataCenter.getKind())
                .apiVersion(dataCenter.getApiVersion())
                .name(dataCenter.getMetadata().getName())
                .uid(dataCenter.getMetadata().getUid())
                .controller(true)
                .blockOwnerDeletion(true);
    }
}
