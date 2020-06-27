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
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Kibana deployment context.
 * A kibana password is generated as a k8s secret if not exists, and a C* role is created if not exists with this password.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ManagedKeyspace {

    /**
     * Keyspace name
     */
    @JsonPropertyDescription("Managed keyspace name")
    @SerializedName("keyspace")
    @Expose
    @EqualsAndHashCode.Include
    private String keyspace;

    /**
     * Target replication factor
     */
    @JsonPropertyDescription("Managed keyspace target replication factor")
    @SerializedName("rf")
    @Expose
    private Integer rf = 1;

    /**
     * Automatic repair
     */
    @JsonPropertyDescription("Managed keyspace automatic repair")
    @SerializedName("repair")
    @Expose
    private Boolean repair = Boolean.TRUE;

    /**
     * CQL Role name, may be null
     */
    @JsonPropertyDescription("CQL Role name, may be null")
    @SerializedName("role")
    @Expose
    private String role;

    /**
     * CQL role is superuser, default is false
     */
    @JsonPropertyDescription("CQL role is superuser, default is false")
    @SerializedName("superuser")
    @Expose
    private Boolean superuser = false;

    /**
     * CQL role is authorized to login, default is true
     */
    @JsonPropertyDescription("CQL role is authorized to login, default is true")
    @SerializedName("login")
    @Expose
    private Boolean login = true;

    /**
     * K8s secret name for the role password.
     */
    @JsonPropertyDescription("K8s secret name for the role password")
    @SerializedName("secretName")
    @Expose
    private String secretName;

    /**
     * K8s secret key for the role password
     */
    @JsonPropertyDescription("K8s secret key for the role password")
    @SerializedName("secretKey")
    @Expose
    private String secretKey;

    /**
     * CQL grant statements
     */
    @JsonPropertyDescription("CQL grant statements")
    @SerializedName("grantStatements")
    @Expose
    private List<String> grantStatements = new ArrayList<>();

}
