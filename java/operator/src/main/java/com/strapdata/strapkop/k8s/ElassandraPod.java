package com.strapdata.strapkop.k8s;

import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
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
                .setSsl(dc.getSpec().getSsl())
                .setEsPort(dc.getSpec().getElasticsearchPort());
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
        return this.name + "/" + this.namespace;
    }

    public static int podIndex(String podName) {
        int index = podName.lastIndexOf("-");
        return Integer.parseInt(podName.substring(index));
    }
}
