package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

/**
 * Played after DC rebuild to reload license and update ES routing table.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ElasticResetTaskSpec {
    /**
     * Update the elasticsearch routing table for the specified indices
     */
    @SerializedName("updateRoutingIndices")
    @Expose
    String updateRoutingIndices;
}
