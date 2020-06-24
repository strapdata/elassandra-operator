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
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.*;

import java.util.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class DataCenterStatus {

    /**
     * The most recent datacenter generation observed by the Elassandra operator.
     */
    @JsonPropertyDescription("Last observed datacenter spec generation")
    @SerializedName("observedGeneration")
    @Expose
    Long observedGeneration;

    /**
     * Last X operations starting from the last finished one.
     */
    @JsonPropertyDescription("Last 16 operations history")
    @SerializedName("operationHistory")
    @Expose
    private List<Operation> operationHistory = new ArrayList<>();

    /**
     * Current desired DC phase
     */
    @JsonPropertyDescription("Current desired datacenter phase")
    @SerializedName("phase")
    @Expose
    private DataCenterPhase phase = DataCenterPhase.RUNNING;

    /**
     * Current DC heath
     */
    @JsonPropertyDescription("Current datacenter health status")
    @SerializedName("health")
    @Expose
    private Health health = Health.UNKNOWN;

    /**
     * True after a datacenter scale up.
     */
    @SerializedName("needCleanup")
    @Expose
    private Boolean needCleanup = false;

    /**
     * Keyspaces requiring cleanup after RF decrease.
     */
    @SerializedName("needCleanupKeyspaces")
    @Expose
    private Set<String> needCleanupKeyspaces = new HashSet<>();

    /**
     * A datacenter is bootstrapped when at least one node has joined the datacenter.
     */
    @JsonPropertyDescription("True for the first datacenter of the cluster or when the datacenter has bootstrapped")
    @SerializedName("bootstrapped")
    @Expose
    private Boolean bootstrapped = false;


    @JsonPropertyDescription("Last error message")
    @SerializedName("lastError")
    @Expose
    private String lastError = null;

    @JsonPropertyDescription("Last error time")
    @SerializedName("lastErrorTime")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date   lastErrorTime = null;

    /**
     * CQL connection status
     */
    @JsonPropertyDescription("CQL connection status")
    @SerializedName("cqlStatus")
    @Expose
    private CqlStatus cqlStatus = CqlStatus.NOT_STARTED;

    @JsonPropertyDescription("Last CQL status message")
    @SerializedName("cqlStatusMessage")
    @Expose
    private String cqlStatusMessage = null;

    /**
     * Current config maps spec fingerprint.
     */
    @JsonPropertyDescription("Current config maps spec fingerprint")
    @SerializedName("configMapFingerPrint")
    @Expose
    private String configMapFingerPrint = null;

    @SerializedName("currentTask")
    @Expose
    private String currentTask = null;

    /**
     * Ordered availability zones
     */
    @JsonPropertyDescription("Ordered list of availability zone")
    @SerializedName("zones")
    @Expose
    private List<String> zones = new ArrayList<>();

    /**
     * Number of replica ready in the datacenter.
     */
    @JsonPropertyDescription("Number of Elassandra nodes ready in the datacenter")
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    /**
     * State of cassandra racks by zone index.
     */
    @JsonPropertyDescription("Cassandra rack statuses")
    @SerializedName("rackStatuses")
    @Expose
    private SortedMap<Integer, RackStatus> rackStatuses =  new TreeMap<>();

    /**
     * keyspace manager status
     */
    @JsonPropertyDescription("Keyspace manager status")
    @SerializedName("keyspaceManagerStatus")
    private KeyspaceManagerStatus keyspaceManagerStatus = new KeyspaceManagerStatus();

    /**
     * Kibana deployed space names
     */
    @JsonPropertyDescription("Kibana space names")
    @SerializedName("kibanaSpaceNames")
    private Set<String> kibanaSpaceNames = new HashSet<>();

    /**
     * Cassandra reaper status
     */
    @JsonPropertyDescription("Cassandra reaper phase")
    @SerializedName("reaperPhase")
    private ReaperPhase reaperPhase = ReaperPhase.NONE;

    public Health health() {
        if (DataCenterPhase.PARKED.equals(this.phase))
            return Health.RED;

        long green = rackStatuses == null ? 0 : rackStatuses.values().stream().filter(r -> Health.GREEN.equals(r.getHealth())).count();
        long yellowOrRed = rackStatuses == null ? 0 : rackStatuses.values().stream().filter(r -> !Health.GREEN.equals(r.getHealth())).count();
        if (green > 0 && yellowOrRed == 0)
            return Health.GREEN;
        if (green > 0 && yellowOrRed == 1)
            return Health.YELLOW;
        return Health.RED;
    }
}