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

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch configuration.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@JsonClassDescription("Elasticsearch configuration")
public class Elasticsearch {

    @JsonPropertyDescription("Enable Elasticsearch, default is true")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    @JsonPropertyDescription("Elasticsearch HTTP port, default is 9200")
    @SerializedName("httpPort")
    @Expose
    private Integer httpPort = 9200;

    @JsonPropertyDescription("Elasticsearch transport port, default is 9300")
    @SerializedName("transportPort")
    @Expose
    private Integer transportPort = 9300;

    @JsonPropertyDescription("Create a Load balancer service with external IP for Elasticsearch, default is false")
    @SerializedName("loadBalancerEnabled")
    @Expose
    private Boolean loadBalancerEnabled = false;

    /**
     * The LoadBalancer exposing cql + elasticsearch nodePorts
     */
    @JsonPropertyDescription("Add a LoadBalancer exposing CQL and elasticsearch HTTP nodePorts")
    @SerializedName("loadBalancerIp")
    @Expose
    private String loadBalancerIp;

    /**
     * Enable Elasticsearch service ingress
     */
    @JsonPropertyDescription("Enable Elasticsearch service ingress")
    @SerializedName("ingressEnabled")
    @Expose
    private Boolean ingressEnabled = false;

    /**
     * Ingress annotations
     */
    @JsonPropertyDescription("Elasticsearch ingress annotations")
    @SerializedName("ingressAnnotations")
    @Expose
    private Map<String, String> ingressAnnotations = null;

    /**
     * Elassandra datacenter group
     */
    @JsonPropertyDescription("Elassandra datacenter group")
    @SerializedName("datacenterGroup")
    @Expose
    private String datacenterGroup = null;

    /**
     * Elassandra datacenter tags
     */
    @JsonPropertyDescription("Elassandra datacenter tags")
    @SerializedName("datacenterTags")
    @Expose
    private List<String> datacenterTags = null;

    /**
     * Elassandra Enterprise configuration
     */
    @JsonPropertyDescription("Elassandra enterprise configuration")
    @SerializedName("enterprise")
    @Expose
    private Enterprise enterprise = new Enterprise()
            .setCbs(false)
            .setHttps(false)
            .setJmx(false)
            .setSsl(false)
            .setAaa(new Aaa().setEnabled(false));
}
