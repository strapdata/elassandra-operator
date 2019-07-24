package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TaskSpec {
    
    @SerializedName("type")
    @Expose
    private String type;
    
    // tasks are elassandra-cluster-scoped
    @SerializedName("cluster")
    @Expose
    private String cluster;
}
