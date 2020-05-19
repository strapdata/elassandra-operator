package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class TaskStatus {

    @SerializedName("phase")
    @Expose
    private TaskPhase phase = TaskPhase.WAITING;

    /**
     * Submit datetime
     */
    @SerializedName("startDate")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date startDate = null;

    @SerializedName("durationInMs")
    @Expose
    private Long durationInMs = null;

    @SerializedName("lastMessage")
    @Expose
    private String lastMessage = null;

    @SerializedName("pods")
    @Expose
    private Map<String, TaskPhase> pods = new HashMap<>();
}
