package com.strapdata.model;

import com.strapdata.model.k8s.cassandra.DataCenter;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ClusterKey extends Key {
    public ClusterKey(final DataCenter dc) {
        super(dc.getSpec().getClusterName(), dc.getMetadata().getNamespace());
    }
    
    public ClusterKey(String name, String namespace) {
        super(name, namespace);
    }
}
