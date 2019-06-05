package com.strapdata.strapkop;

import com.instaclustr.model.Key;
import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.instaclustr.model.sidecar.NodeStatus;
import io.kubernetes.client.models.V1Pod;

@SuppressWarnings("WeakerAccess")
public class CassandraNodeStatusEvent {
    public final V1Pod pod;
    public final Key<DataCenter> dataCenterKey;
    public final NodeStatus previousMode;
    public final NodeStatus currentMode;

    public CassandraNodeStatusEvent(final V1Pod pod, final Key<DataCenter> dataCenterKey, final NodeStatus previousMode, final NodeStatus currentMode) {
        this.pod = pod;
        this.dataCenterKey = dataCenterKey;
        this.previousMode = previousMode;
        this.currentMode = currentMode;
    }
}
