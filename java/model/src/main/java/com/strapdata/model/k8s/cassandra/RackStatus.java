package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RackStatus {
    
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Current rack phase
     */
    @SerializedName("phase")
    @Expose
    private RackPhase phase = RackPhase.CREATING;

    /**
     * Number of joined nodes.
     */
    @SerializedName("joinedReplicas")
    @Expose
    private Integer joinedReplicas = 0;

}
