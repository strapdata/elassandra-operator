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
import com.google.common.base.Strings;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * External DNS configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class ExternalDns {

    /**
     * Enable external DNS support.
     *
     */
    @JsonPropertyDescription("Enable External DNS configuration")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Unique root for DNS hostname generation.
     * For cassandra seed nodes : cassandra-x-0.domain, cassandra-x-1.domain
     * For elasticsearch: elasticsearch-x.domain
     * For kibana: kibana-x.domain
     * For reaper: repear-x.domain
     */
    @JsonPropertyDescription("Unique root used to publish DNS names for elassandra seed nodes in the form cassandra-{root}-{idx}.{domain}")
    @SerializedName("root")
    @Expose
    private String root;

    /**
     * External dns domain;
     */
    @JsonPropertyDescription("External DNS domain")
    @SerializedName("domain")
    @Expose
    private String domain;

    /**
     * External DNS ttl
     */
    @JsonPropertyDescription("External DNS record TTL")
    @SerializedName("ttl")
    @Expose
    private Integer ttl = 300;

    public void validate() {
        if (enabled) {
            if (Strings.isNullOrEmpty(domain))
                throw new IllegalArgumentException("externalDns is enabled but no DNS domain is configured, please fix your elassandra CRD");
            if (ttl == null || ttl < 0)
                throw new IllegalArgumentException("externalDns is enabled but no DNS TTL is configured, please fix your elassandra CRD");
        }
    }

}
