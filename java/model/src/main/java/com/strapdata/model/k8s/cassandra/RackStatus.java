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
    
    @SerializedName("ready")
    @Expose
    private Boolean ready;
    
    @SerializedName("mode")
    @Expose
    private RackMode mode;
    
    @SerializedName("replicas")
    @Expose
    private Integer replicas;

    /**
     * Track if the seed (the first node in the rack) has already bootstrapped.
     * When seedBootstrapped = true, the rack first pod is included in local seeds.
     * Should become true when the first rack pod become NORMAL.
     */
    @SerializedName("seedBootstrapped")
    @Expose
    private Boolean seedBootstrapped = false;

}
