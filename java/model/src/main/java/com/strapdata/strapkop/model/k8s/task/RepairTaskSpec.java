package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RepairTaskSpec {

    @SerializedName("keyspace")
    @Expose
    String keyspace;

    /**
     * Wait interval between node repair, 10s by default
     */
    @SerializedName("waitIntervalInSec")
    @Expose
    Long waitIntervalInSec = 10L;
}

