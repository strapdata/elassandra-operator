package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

/**
 * After DC rebuild, reload license and update ES routing table.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class UpdateRoutingTaskSpec {
    /**
     * Update the elasticsearch routing table for the specified indices expression
     */
    @SerializedName("indices")
    @Expose
    String indices;
}
