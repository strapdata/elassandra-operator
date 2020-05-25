package com.strapdata.strapkop.model.fabric8.datacenter;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenterSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

// see https://github.com/fabric8io/kubernetes-client/tree/master/kubernetes-examples/src/main/java/io/fabric8/kubernetes/examples/crds
@ToString
@Getter
@Setter
public class DataCenter extends CustomResource {
    private DataCenterSpec spec;
    private DataCenterStatus status;

    @Override
    public ObjectMeta getMetadata() { return super.getMetadata(); }
}
