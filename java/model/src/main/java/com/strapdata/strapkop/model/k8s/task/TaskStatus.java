package com.strapdata.strapkop.model.k8s.task;

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
    private TaskPhase phase = TaskPhase.WAITING;
    
    @SerializedName("lastMessage")
    @Expose
    private String lastMessage = null;
    
    @SerializedName("pods")
    @Expose
    private Map<String, TaskPhase> pods = new HashMap<>();
}
