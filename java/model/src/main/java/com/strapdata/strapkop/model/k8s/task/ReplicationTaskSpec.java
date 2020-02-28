package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Wither;

import java.util.Map;

/**
 * Remove a datacenter from replication map, before removing DC
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ReplicationTaskSpec {

    /**
     * Add or remove the datacenter.
     */
    @SerializedName("action")
    @Expose
    Action action;

    /**
     * datacenter to remove from the replication map.
     */
    @SerializedName("dcName")
    @Expose
    String dcName;

    /**
     * Destination datacenter number of nodes
     */
    @SerializedName("dcSize")
    @Expose
    private int dcSize;

    /**
     * Replication map for rebuild keyspaces (both system and user keyspaces)
     */
    @SerializedName("replicationMap")
    @Expose
    private Map<String, Integer> replicationMap;

    public enum Action {
        ADD,    // add dc
        REMOVE  // remove dc
    }
}
