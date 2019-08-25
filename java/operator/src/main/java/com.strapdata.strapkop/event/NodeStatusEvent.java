package com.strapdata.strapkop.event;

import com.strapdata.model.sidecar.ElassandraNodeStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NodeStatusEvent {
    private ElassandraPod pod;
    private ElassandraNodeStatus previousMode;
    private ElassandraNodeStatus currentMode;
}
