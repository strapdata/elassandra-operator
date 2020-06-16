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
public class Enterprise {

    @JsonPropertyDescription("Enable Elassandra Enterprise")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    @JsonPropertyDescription("Enable JMX for Elasticsearch metrics")
    @SerializedName("jmx")
    @Expose
    private Boolean jmx = true;

    @JsonPropertyDescription("Enable HTTPS for Elasticsearch")
    @SerializedName("https")
    @Expose
    private Boolean https = true;

    @JsonPropertyDescription("Enable TLS for Elasticsearch transport connections")
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = true;

    @JsonPropertyDescription("Enable Elasticsearch Authentication, Authorization and Accounting")
    @SerializedName("aaa")
    @Expose
    private Aaa aaa;

    @JsonPropertyDescription("Enable Elasticsearch Content-Based security")
    @SerializedName("cbs")
    @Expose
    private Boolean cbs = true;
}
