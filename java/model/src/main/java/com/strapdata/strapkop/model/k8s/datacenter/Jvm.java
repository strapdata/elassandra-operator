/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

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
