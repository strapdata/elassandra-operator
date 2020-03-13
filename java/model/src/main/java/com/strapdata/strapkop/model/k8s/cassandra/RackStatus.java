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

    /**
     * Rack name (or availability zone name)
     */
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Rack index starting at 0 (Build form the DataCenterStatus.zones)
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
     * Datacenter spec and user configmap fingerprint
     */
    @SerializedName("fingerprint")
    @Expose
    private String fingerprint = null;

    /**
     * Number of replica desired in the underlying sts.
     */
    @SerializedName("desiredReplicas")
    @Expose
    private Integer desiredReplicas = 0;

    /**
     * Number of replica ready in the underlying sts.
     */
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    /**
     * Number of parked pods (size of the sts when not parked).
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
