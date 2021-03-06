/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.k8s;

import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import io.kubernetes.client.openapi.models.V1OwnerReference;

import java.util.Locale;
import java.util.UUID;

public class OperatorNames {

    public static final String CQL_PORT_NAME = "cql";
    public static final String STORAGE_PORT_NAME = "internode";
    public static final String ELASTICSEARCH_PORT_NAME = "elasticsearch";
    public static final String ELASTICSEARCH_TRANSPORT_PORT_NAME = "transport";
    public static final String PROMETHEUS_PORT_NAME = "prometheus";

    public static String configMapUniqueName(final String name, final String fingerprint) {
        return name + "-" + fingerprint;
    }

    public static String clusterChildObjectName(final String nameFormat, final DataCenter dataCenter) {
        return String.format(nameFormat, "elassandra-" + dataCenter.getSpec().getClusterName());
    }

    public static String dataCenterResource(final DataCenterSpec dataCenterSpec) {
        return dataCenterResource(dataCenterSpec.getClusterName(), dataCenterSpec.getDatacenterName());
    }

    public static String dataCenterResource(final String clusterName, final String datacenterName) {
        return "elassandra-" + clusterName + "-" + datacenterName;
    }

    public static String rackResource(final String clusterName, final String datacenterName, final String rack) {
        return "elassandra-" + clusterName + "-" + datacenterName + "-" +rack;
    }

    public static String nodeInfoServiceAccount(final DataCenter dataCenter) {
        return dataCenterResource(dataCenter.getSpec()) + "-nodeinfo";
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

    public static String rackChildObjectIndex(final String nameFormat, final DataCenter dataCenter, final int rackIndex) {
        return String.format(nameFormat,
                "elassandra-" + dataCenter.getSpec().getClusterName()
                        + "-" + dataCenter.getSpec().getDatacenterName()
                        + "-" + rackIndex);
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

    public static String adminService(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-admin", dataCenter);
    }

    public static String seedsService(DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }

    public static String seedConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-seeds", dataCenter);
    }

    public static String operatorConfig(final DataCenter dataCenter) {
        return OperatorNames.dataCenterChildObjectName("%s-operator", dataCenter);
    }

    public static String rackConfig(final DataCenter dataCenter, final String rack) {
        return OperatorNames.rackChildObjectName("%s", dataCenter, rack);
    }

    public static String stsName(final DataCenter dataCenter, final int rack) {
        return OperatorNames.rackChildObjectIndex("%s", dataCenter, rack);
    }

    public static String podName(final DataCenter dataCenter, final int rack, int podIndex) {
        return OperatorNames.rackChildObjectIndex("%s-" + podIndex, dataCenter, rack);
    }

    public static String podFqdn(final DataCenter dc, final String podName) {
        return String.format(Locale.ROOT,"%s.%s.%s.svc.cluster.local", podName, OperatorNames.nodesService(dc), dc.getMetadata().getNamespace());
    }

    public static String podFqdn(final String namespace, final String clusterName, String dcName, final String podName) {
        return String.format(Locale.ROOT, "%s.%s.%s.svc.cluster.local", podName, dataCenterResource(clusterName, dcName), namespace);
    }

    public static String internalPodFqdn(DataCenter dc, int rackIndex, int podIndex) {
        return String.format(Locale.ROOT, "elassandra-%s-%s-%d-%d.%s.%s.svc.cluster.local",
                dc.getSpec().getClusterName().toLowerCase(Locale.ROOT),
                dc.getSpec().getDatacenterName().toLowerCase(Locale.ROOT),
                rackIndex,
                podIndex,
                OperatorNames.nodesService(dc),
                dc.getMetadata().getNamespace());
    }

    public static String externalPodFqdn(DataCenter dc, int rackIndex, int podIndex) {
        return String.format(Locale.ROOT, "cassandra-%s-%d-%d.%s",
                dc.getSpec().getNetworking().getExternalDns().getRoot(),
                rackIndex,
                podIndex,
                dc.getSpec().getNetworking().getExternalDns().getDomain());
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

    public static V1OwnerReference ownerReference(String name, String uid, String version) {
        return new V1OwnerReference()
                .kind(DataCenter.KIND)
                .apiVersion(version)
                .name(name)
                .uid(uid)
                .controller(true)
                .blockOwnerDeletion(true);
    }
}
