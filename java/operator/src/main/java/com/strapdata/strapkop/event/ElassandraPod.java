package com.strapdata.strapkop.event;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorNames;
import lombok.Data;

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
    private String rack;

    final static Pattern podNamePattern = Pattern.compile("elassandra-([\\w]+)-([\\w]+)-([\\w-]+)-([\\d]+)");

    public ElassandraPod(final DataCenter dc, final String rack, final int index) {
        this.setName(OperatorNames.podName(dc, rack, index))
                .setFqdn(OperatorNames.podFqdn(dc, OperatorNames.podName(dc, rack, index)))
                .setCluster(dc.getSpec().getClusterName())
                .setDataCenter(dc.getSpec().getDatacenterName())
                .setParent(dc.getMetadata().getName())
                .setNamespace(dc.getMetadata().getNamespace())
                .setRack(rack);
    }

    public ElassandraPod(final String namespace, final String clusterName, String dcName, String podName) {
        this.setName(podName)
                .setFqdn(OperatorNames.podFqdn(namespace, clusterName, dcName, podName))
                .setCluster(clusterName)
                .setDataCenter(dcName)
                .setParent("elassandra-"+clusterName+"-"+dcName)
                .setNamespace(namespace);
    }

    public static ElassandraPod fromName(final DataCenter dc, final String podName) {
        Matcher matcher = podNamePattern.matcher(podName);
        if (matcher.matches())
            return new ElassandraPod(dc, matcher.group(3), Integer.parseInt(matcher.group(4)));
        throw new IllegalArgumentException("Pod name="+podName+" does not match expected regular expression");
    }

    public static ElassandraPod fromName(final String namespace, final String podName) {
        Matcher matcher = podNamePattern.matcher(podName);
        if (matcher.matches())
            return new ElassandraPod(namespace, matcher.group(1), matcher.group(2), podName);
        throw new IllegalArgumentException("Pod name="+podName+" does not match expected regular expression");
    }
}
