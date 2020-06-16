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
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Reconciliation operation started to reach the desired state.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Operation {

    /**
     * Operation description
     */
    @SerializedName("triggeredBy")
    @Expose
    private String triggeredBy;

    @SerializedName("actions")
    @Expose
    private List<String> actions = new ArrayList<>();

    /**
     * Submit datetime
     */
    @SerializedName("submitDate")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date submitDate = null;

    /**
     * Pending time in millisecond
     */
    @SerializedName("pendingInMs")
    @Expose
    private Long pendingInMs = null;

    /**
     * Operation duration in millisecond
     */
    @SerializedName("durationInMs")
    @Expose
    private Long durationInMs = null;
}
