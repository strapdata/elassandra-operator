package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

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

    /**
     * Host id of the seed node in the rack.
     */
    @SerializedName("seedHostId")
    @Expose
    private UUID seedHostId = UUID.randomUUID();
}
