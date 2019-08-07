package com.strapdata.strapkop.event;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorNames;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ElassandraPod {
    
    private String name;
    private String fqdn;

    private String dataCenter;
    private String cluster;
    private String namespace;
    private String parent;
    
    public static ElassandraPod create(final DataCenter dc, final String podName) {
        return new ElassandraPod()
                .setName(podName)
                .setFqdn(OperatorNames.podFqdn(dc, podName))
                .setCluster(dc.getSpec().getClusterName())
                .setDataCenter(dc.getSpec().getDatacenterName())
                .setParent(dc.getMetadata().getName())
                .setNamespace(dc.getMetadata().getNamespace());
    }
}
