package com.strapdata.strapkop.event;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorNames;
import lombok.Data;

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
    
    public ElassandraPod(final DataCenter dc, final String podName) {
        this.setName(podName)
                .setFqdn(OperatorNames.podFqdn(dc, podName))
                .setCluster(dc.getSpec().getClusterName())
                .setDataCenter(dc.getSpec().getDatacenterName())
                .setParent(dc.getMetadata().getName())
                .setNamespace(dc.getMetadata().getNamespace());
    }
}
