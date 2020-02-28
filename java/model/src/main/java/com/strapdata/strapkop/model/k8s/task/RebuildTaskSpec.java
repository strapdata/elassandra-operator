package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Wither;

/**
 * Stream data from a source dc
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RebuildTaskSpec {

    /**
     * Source datacenter name for streaming
     */
    @SerializedName("srcDcName")
    @Expose
    private String srcDcName;

    /**
     * rebuild specific keyspace
     */
    @SerializedName("keyspace")
    @Expose
    private String keyspace;
}
