package com.strapdata.strapkop.event;

import com.strapdata.model.sidecar.NodeStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NodeStatusEvent {
    private ElassandraPod pod;
    private NodeStatus previousMode;
    private NodeStatus currentMode;
}
