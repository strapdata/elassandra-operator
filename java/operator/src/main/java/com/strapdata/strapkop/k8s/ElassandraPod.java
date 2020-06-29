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

import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Representation of an Elassandra node/pod, for identification
 */
@Data
public class ElassandraPod {

    private String name;
    private String fqdn;

    private String dataCenter;
    private String cluster;
    private String namespace;
    private String parent;

    // rack & ssl not always initialize and this object is define as a key in ElassandraPod cache, exclude these fields
    @EqualsAndHashCode.Exclude
    private int rackIndex;

    @EqualsAndHashCode.Exclude
    private boolean ssl = false;

    @EqualsAndHashCode.Exclude
    private int esPort = 9200;

    final public static Pattern podNamePattern = Pattern.compile("elassandra-([\\w]+)-([\\w]+)-([\\w-]+)-([\\d]+)");

    public ElassandraPod(final DataCenter dc, final int rackIndex, final int index) {
        this.setName(OperatorNames.podName(dc, rackIndex, index))
                .setFqdn(OperatorNames.podFqdn(dc, OperatorNames.podName(dc, rackIndex, index)))
                .setCluster(dc.getSpec().getClusterName())
                .setDataCenter(dc.getSpec().getDatacenterName())
                .setParent(dc.getMetadata().getName())
                .setNamespace(dc.getMetadata().getNamespace())
                .setRackIndex(rackIndex)
                .setSsl(dc.getSpec().getCassandra().getSsl())
                .setEsPort(dc.getSpec().getElasticsearch().getHttpPort());
    }

    public ElassandraPod(final String namespace, final String clusterName, String dcName, String podName) {
        this.setName(podName)
                .setFqdn(OperatorNames.podFqdn(namespace, clusterName, dcName, podName))
                .setCluster(clusterName)
                .setDataCenter(dcName)
                .setParent("elassandra-" + clusterName + "-" + dcName)
                .setNamespace(namespace);
    }

    public static ElassandraPod fromV1Pod(final V1Pod pod) {
        V1ObjectMeta metadata = pod.getMetadata();
        Matcher matcher = podNamePattern.matcher(metadata.getName());
        if (matcher.matches()) {
            Map<String, String> labels = metadata.getLabels();
            return new ElassandraPod(metadata.getNamespace(),
                    labels.get(OperatorLabels.CLUSTER),
                    labels.get(OperatorLabels.DATACENTER),
                    metadata.getName())
                    .setRackIndex(Integer.parseInt(labels.get(OperatorLabels.RACKINDEX)));
        }
        throw new IllegalArgumentException("Pod name=" + metadata.getName() + " does not match expected regular expression");
    }

    public static ElassandraPod fromName(final DataCenter dc, final String podName) {
        Matcher matcher = podNamePattern.matcher(podName);
        if (matcher.matches()) {
            return new ElassandraPod(dc, Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
        }
        throw new IllegalArgumentException("Pod name=" + podName + " does not match expected regular expression");
    }

    public static ElassandraPod fromName(final String namespace, final String podName) {
        Matcher matcher = podNamePattern.matcher(podName);
        if (matcher.matches())
            return new ElassandraPod(namespace, matcher.group(1), matcher.group(2), podName);
        throw new IllegalArgumentException("Pod name=" + podName + " does not match expected regular expression");
    }

    public String dataCenterName() {
        return "elassandra-" + this.cluster + "-" + this.dataCenter;
    }

    public String id() {
        return this.namespace + "/" + this.name;
    }
}
