package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Prometheus settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Prometheus {
    /**
     * Enable Prometheus support.
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;

    /**
     * Prometheus agent listen port.
     */
    @SerializedName("port")
    @Expose
    private Integer port = 9500;
}
