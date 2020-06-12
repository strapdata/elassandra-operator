package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
    @SerializedName("triggeredBy")
    @Expose
    private String triggeredBy;

    @SerializedName("actions")
    @Expose
    private List<String> actions = new ArrayList<>();

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
