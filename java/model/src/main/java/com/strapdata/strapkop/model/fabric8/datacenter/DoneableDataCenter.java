package com.strapdata.strapkop.model.fabric8.datacenter;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;

public class DoneableDataCenter extends CustomResourceDoneable<DataCenter> {
    public DoneableDataCenter(DataCenter resource, Function<DataCenter, DataCenter> function) {
        super(resource, function);
    }
}
