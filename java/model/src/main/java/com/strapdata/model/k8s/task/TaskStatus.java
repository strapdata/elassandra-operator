package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
public class TaskStatus {
    
    @SerializedName("phase")
    @Expose
    private TaskPhase phase = null;
    
    @SerializedName("lastErrorMessage")
    @Expose
    private String lastErrorMessage = null;
    
    @SerializedName("pods")
    @Expose
    private Map<String, TaskPhase> pods = null;
}
