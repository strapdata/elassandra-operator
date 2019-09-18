package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Cassandra reaper default configuration.
 */
@Data
@NoArgsConstructor
public class Reaper {

    /**
     * Reaper docker image;
     */
    @SerializedName("image")
    @Expose
    private String image  = "thelastpickle/cassandra-reaper:1.4.4";

    /**
     * Reaper JWT secret
     */
    @SerializedName("jwtSecret")
    @Expose
    private String jwtSecret = UUID.randomUUID().toString();

}
