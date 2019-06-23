package com.strapdata.strapkop.event;

import com.strapdata.model.Key;
import com.strapdata.model.sidecar.NodeStatus;
import io.kubernetes.client.models.V1Pod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NodeStatusEvent {
    private V1Pod pod;
    private Key dataCenterKey;
    private NodeStatus previousMode;
    private NodeStatus currentMode;
}
