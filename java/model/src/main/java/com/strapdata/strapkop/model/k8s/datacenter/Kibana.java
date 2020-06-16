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

import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra reaper default configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Kibana {

    /**
     * Kibana docker image;
     */
    @JsonPropertyDescription("Kibana docker image")
    @SerializedName("image")
    @Expose
    private String image  = "docker.elastic.co/kibana/kibana-oss:6.2.3";

    /**
     * Enable Kibana support.
     *
     */
    @JsonPropertyDescription("Kibana enabled, default is true")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Kibana user spaces (key = space name)
     */
    @JsonPropertyDescription("Kibana user spaces")
    @SerializedName("spaces")
    @Expose
    private List<KibanaSpace> spaces = new ArrayList<>();
}
