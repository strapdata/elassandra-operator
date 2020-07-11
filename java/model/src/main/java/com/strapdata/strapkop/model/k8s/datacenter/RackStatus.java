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

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class RackStatus {

    /**
     * Rack name (or availability zone name)
     */
    @JsonPropertyDescription("Rack name (or availability zone name)")
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Rack index starting at 0 (Build form the DataCenterStatus.zones)
     */
    @JsonPropertyDescription("Rack index starting at 0")
    @SerializedName("index")
    @Expose
    private Integer index;

    /**
     * Rack progress state
     */
    @JsonPropertyDescription("Rack progress state")
    @SerializedName("progressState")
    @Expose
    private ProgressState progressState = ProgressState.RUNNING;

    /**
     * Current DC heath
     */
    @JsonPropertyDescription("Current DC heath")
    @SerializedName("health")
    @Expose
    private Health health = Health.UNKNOWN;

    /**
     * Datacenter spec fingerprint - operator configmap fingerprint - user configmap fingerprint
     */
    @JsonPropertyDescription("Rack fingerprint")
    @SerializedName("fingerprint")
    @Expose
    private String fingerprint = null;

    /**
     * Number of replica desired in the underlying sts.
     */
    @JsonPropertyDescription("Number of replica in the underlying StatefulSet")
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 0;

    /**
     * Number of replica ready in the underlying sts.
     */
    @JsonPropertyDescription("Number of replica ready in the underlying StatefulSet")
    @SerializedName("readyReplicas")
    @Expose
    private Integer readyReplicas = 0;

    public Health health() {
        if (readyReplicas != null && replicas != null && replicas == readyReplicas)
            return Health.GREEN;
        if (readyReplicas != null && readyReplicas > 0 && replicas != null && replicas - readyReplicas == 1)
            return Health.YELLOW;
        return Health.RED;
    }
}
