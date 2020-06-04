package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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
    @JsonPropertyDescription("Enable Prometheus support")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;

    /**
     * Prometheus agent listen port.
     */
    @JsonPropertyDescription("Prometheus agent listen port, default is 9500")
    @SerializedName("port")
    @Expose
    private Integer port = 9500;
}
