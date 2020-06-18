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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Network settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Networking {
    /**
     * Enable hostPort for nativePort, storagePort and sslStoragePort
     */
    @JsonPropertyDescription("Enable K8S hostPort")
    @SerializedName("hostPortEnabled")
    @Expose
    private Boolean hostPortEnabled = true;

    /**
     * Enable hostNetwork, allowing to bind on host IP addresses.
     */
    @JsonPropertyDescription("Enable K8S hostNetwork")
    @SerializedName("hostNetworkEnabled")
    @Expose
    private Boolean hostNetworkEnabled = false;

    /**
     * External DNS config for public nodes and elasticsearch service.
     */
    @JsonPropertyDescription("External DNS configuration")
    @SerializedName("externalDns")
    @Expose
    private ExternalDns externalDns = null;

    @JsonIgnore
    public boolean nodeInfoRequired() {
        return hostPortEnabled || hostNetworkEnabled || (externalDns != null && externalDns.getEnabled());
    }

}
