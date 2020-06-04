package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * JVM settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Jvm {

    /**
     * Automatic heap and GC configuration
     */
    @JsonPropertyDescription("Automatic heap and GC configuration")
    @SerializedName("computeJvmMemorySettings")
    @Expose
    private boolean computeJvmMemorySettings = true;

    /**
     * Java JMX port
     */
    @JsonPropertyDescription("Java JMX port")
    @SerializedName("jmxPort")
    @Expose
    private Integer jmxPort = 7199;

    /**
     * Enable JMXMP.
     */
    @JsonPropertyDescription("Enable JMXMP")
    @SerializedName("jmxmpEnabled")
    @Expose
    private Boolean jmxmpEnabled = true;

    /**
     * Enable SSL on JMXMP.
     */
    @JsonPropertyDescription("Enable SSL on JMXMP")
    @SerializedName("jmxmpOverSSL")
    @Expose
    private Boolean jmxmpOverSSL = true;

    /**
     * Java debugger port (also hostPort)
     */
    @JsonPropertyDescription("Java debugger port, default is disabled")
    @SerializedName("jdbPort")
    @Expose
    private Integer jdbPort = -1;
}
