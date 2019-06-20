package com.strapdata.strapkop;

import com.strapdata.model.Key;
import com.strapdata.model.sidecar.NodeStatus;
import io.kubernetes.client.models.V1Pod;

@SuppressWarnings("WeakerAccess")
public class CassandraNodeStatusEvent {
    public final V1Pod pod;
    public final Key dataCenterKey;
    public final NodeStatus previousMode;
    public final NodeStatus currentMode;

    public CassandraNodeStatusEvent(final V1Pod pod, final Key dataCenterKey, final NodeStatus previousMode, final NodeStatus currentMode) {
        this.pod = pod;
        this.dataCenterKey = dataCenterKey;
        this.previousMode = previousMode;
        this.currentMode = currentMode;
    }
}
