package com.strapdata.strapkop.model.sidecar;

import com.google.gson.annotations.Expose;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StatusResponse {
    @Expose
    private ElassandraNodeStatus status;
}
