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

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra settings
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Cassandra {

    /**
     * Cassandra workload
     */
    @SerializedName("workload")
    @Expose
    private Workload workload = Workload.READ_WRITE;

    /**
     * Enable cassandra/ldap authentication and authorization
     */
    @SerializedName("authentication")
    @Expose
    private Authentication authentication = Authentication.CASSANDRA;

    /**
     * Enable SSL support
     */
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;

    /**
     * Play cassandra commitlogs in a dedicated init container to avoid the liveness timeout endless loop.
     */
    @SerializedName("commitlogsInitContainer")
    @Expose
    private Boolean commitlogsInitContainer = false;

    /**
     *  Tell Cassandra to use the local IP address (INTERNAL_IP).
     *  May require a VPC  or VPN between the datacenters.
     */
    @SerializedName("snitchPreferLocal")
    @Expose
    private Boolean snitchPreferLocal = true;

    /**
     * Remote seed IP addresses.
     */
    @SerializedName("remoteSeeds")
    @Expose
    private List<String> remoteSeeds = new ArrayList<>();

    /**
     * List of URL providing dynamic seed list.
     */
    @SerializedName("remoteSeeders")
    @Expose
    private List<String> remoteSeeders = new ArrayList<>();

    /**
     * CQL native port (also hostPort)
     */
    @SerializedName("nativePort")
    @Expose
    private Integer nativePort = 39042;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("storagePort")
    @Expose
    private Integer storagePort = 37000;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("sslStoragePort")
    @Expose
    private Integer sslStoragePort = 37001;
}
