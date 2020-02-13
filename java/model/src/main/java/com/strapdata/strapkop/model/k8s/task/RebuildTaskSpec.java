package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.Map;

/**
 * Task should be manually published when starting en new DC.
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class RebuildTaskSpec {

    /**
     * Source datacenter name for streaming
     */
    @SerializedName("srcDcName")
    @Expose
    private String srcDcName;

    /**
     * Destination datacenter name for streaming
     */
    @SerializedName("dstDcName")
    @Expose
    private String dstDcName;

    /**
     * Destination datacenter number of nodes
     */
    @SerializedName("dstDcSize")
    @Expose
    private int dstDcSize;

    /**
     * Replication map for rebuild keyspaces (both system and user keyspaces)
     */
    @SerializedName("replicationMap")
    @Expose
    private Map<String, Integer> replicationMap;

}
