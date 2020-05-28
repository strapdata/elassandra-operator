package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Jvm {
    /**
     * JVM options properties
     */
    @SerializedName("options")
    @Expose
    private List<String> options = new ArrayList<>();

    /**
     * Automatic heap and GC configuration
     */
    @SerializedName("computeJvmMemorySettings")
    @Expose
    private boolean computeJvmMemorySettings = true;

    /**
     * Java JMX port
     */
    @SerializedName("jmxPort")
    @Expose
    private Integer jmxPort = 7199;

    /**
     * Enable JMXMP.
     */
    @SerializedName("jmxmpEnabled")
    @Expose
    private Boolean jmxmpEnabled = true;

    /**
     * Enable SSL with JMXMP.
     */
    @SerializedName("jmxmpOverSSL")
    @Expose
    private Boolean jmxmpOverSSL = true;

    /**
     * Java debugger port (also hostPort)
     */
    @SerializedName("jdbPort")
    @Expose
    private Integer jdbPort = -1;
}
