package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Date;

/**
 * Reconciliation operation started to reach the desired state.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Operation {

    /**
     * Operation description
     */
    @SerializedName("desc")
    @Expose
    private String desc;

    /**
     * Submit datetime
     */
    @SerializedName("submitDate")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date submitDate = null;

    /**
     * Pending time in millisecond
     */
    @SerializedName("pendingInMs")
    @Expose
    private Long pendingInMs = null;

    /**
     * Operation duration in millisecond
     */
    @SerializedName("durationInMs")
    @Expose
    private Long durationInMs = null;
}
