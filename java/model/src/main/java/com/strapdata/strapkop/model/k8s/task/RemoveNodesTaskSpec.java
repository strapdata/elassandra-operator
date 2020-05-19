package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

/**
 * Remove a datacenter from the ring
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RemoveNodesTaskSpec {

    /**
     * datacenter to remove from the ring.
     */
    @SerializedName("dcName")
    @Expose
    String dcName;

}
