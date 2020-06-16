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
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonIsoDateAdapter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class TaskStatus {

    /**
     * The most recent task generation observed by the Elassandra operator.
     */
    @SerializedName("observedGeneration")
    @Expose
    Long observedGeneration;

    /**
     * Task phase
     */
    @SerializedName("phase")
    @Expose
    private TaskPhase phase = TaskPhase.WAITING;

    /**
     * Submit datetime
     */
    @SerializedName("startDate")
    @Expose
    @JsonAdapter(GsonIsoDateAdapter.class)
    private Date startDate = null;

    @SerializedName("durationInMs")
    @Expose
    private Long durationInMs = null;

    @SerializedName("lastMessage")
    @Expose
    private String lastMessage = null;

    @SerializedName("pods")
    @Expose
    private Map<String, TaskPhase> pods = new HashMap<>();
}
