package com.strapdata.strapkop.event;

import com.strapdata.model.sidecar.ElassandraPodStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NodeStatusEvent {
    private ElassandraPod pod;
    private ElassandraPodStatus previousMode;
    private ElassandraPodStatus currentMode;
}
