package com.strapdata.strapkop.model;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ClusterKey extends Key {
    public ClusterKey(final DataCenter dc) {
        super(dc.getSpec().getClusterName(), dc.getMetadata().getNamespace());
    }

    public ClusterKey(String name, String namespace) {
        super(name, namespace);
    }
}
