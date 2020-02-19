package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Wither;

/**
 * Remove a datacenter from replication map, before removing DC
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RemoveReplicationTaskSpec {

    /**
     * datacenter to remove from the replication map.
     */
    @SerializedName("dcName")
    @Expose
    String dcName;

}
