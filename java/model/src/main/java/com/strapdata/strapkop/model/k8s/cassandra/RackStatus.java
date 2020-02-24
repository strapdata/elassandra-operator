package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.UUID;

@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class RackStatus {
    
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Rack index starting at 0
     */
    @SerializedName("index")
    @Expose
    private Integer index;

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
     * Number of parked pods.
     */
    @SerializedName("parkedReplicas")
    @Expose
    private Integer parkedReplicas = 0;
    /**
     * Host id of the seed node in the rack.
     */
    @SerializedName("seedHostId")
    @Expose
    private UUID seedHostId = UUID.randomUUID();

    public boolean isParked() {
        return RackPhase.PARKED.equals(phase);
    }

    public  boolean isRunning() {
        return RackPhase.RUNNING.equals(phase);
    }
}
