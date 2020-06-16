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

package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

import java.util.Map;

/**
 * Remove a datacenter from replication map, before removing DC
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ReplicationTaskSpec {

    /**
     * Add or remove the datacenter.
     */
    @SerializedName("action")
    @Expose
    Action action;

    /**
     * datacenter to remove from the replication map.
     */
    @SerializedName("dcName")
    @Expose
    String dcName;

    /**
     * Destination datacenter number of nodes
     */
    @SerializedName("dcSize")
    @Expose
    private int dcSize;

    /**
     * Replication map for rebuild keyspaces (both system and user keyspaces)
     */
    @SerializedName("replicationMap")
    @Expose
    private Map<String, Integer> replicationMap;

    public enum Action {
        ADD,    // add dc
        REMOVE  // remove dc
    }
}
