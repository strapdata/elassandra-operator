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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import lombok.*;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.*;

/**
 * Kibana deployment context.
 * A kibana password is generated as a k8s secret if not exists, and a C* role is created if not exists with this password.
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class KibanaSpace {

    private static final String KIBANA_INDEX_PREFIX = ".kibana";
    private static final String KIBANA_KEYSPACE_PREFIX = "_kibana";

    public static final String KIBANA_PREFIX = "kibana";
    public static final String KIBANA_APP_PREFIX = "kibana";

    /**
     * Kibana space name (default is "")
     */
    @JsonPropertyDescription("Kibana space name (default is \"\")")
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Visible keyspaces
     */
    @JsonPropertyDescription("Kibana visible keyspaces")
    @SerializedName("keyspaces")
    @Expose
    private Set<String> keyspaces = new HashSet<>();

    @JsonPropertyDescription("Kibana authorization statements")
    @SerializedName("statements")
    @Expose
    private List<String> statements = new ArrayList<>();

    /**
     * Number of kibana instance, default is 1
     */
    @JsonPropertyDescription("Number of kibana instance, default is 1")
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 1;

    /**
     * Kibana ingress suffix (concatened with kibana spaces).
     * host: {spaceName}-{ingressSuffix}
     */
    @JsonPropertyDescription("Kibana space ingress suffix so that ingress host = {spaceName}-{ingressSuffix}")
    @SerializedName("ingressSuffix")
    @Expose
    private String ingressSuffix = null;

    /**
     * Ingress annotations
     */
    @JsonPropertyDescription("Kibana space ingress annotations")
    @SerializedName("ingressAnnotations")
    @Expose
    private Map<String, String> ingressAnnotations = null;

    /**
     * Kibana upgrade version for Elasticsearch 6.5+
     * Should be 1 starting with elasticsearch 6.8
     * See https://www.elastic.co/guide/en/kibana/current/upgrade-migrations.html
     */
    @JsonPropertyDescription("Kibana space Elasticsearch upgrade version")
    @SerializedName("version")
    @Expose
    private Integer version = 1;

    /**
     * PodTemplate provides pod customisation (labels, resource, annotations, affinity rules, resource, priorityClassName, serviceAccountName) for the kibana pods
     */
    @JsonPropertyDescription("Kibana pod template allowing customisation")
    @SerializedName("podTemplate")
    @Expose
    private V1PodTemplateSpec podTemplate = new V1PodTemplateSpec();

    /**
     * Manage kibana index name depending on elasticsearch version
     * See https://www.elastic.co/guide/en/kibana/current/upgrade-migrations.html
     * @param version
     * @return
     */
    @JsonIgnore
    public String index(Integer version) {
        return KIBANA_INDEX_PREFIX + (Strings.isNullOrEmpty(name) ? "" : "-" + name) + (version == null ? "" : "_"+version);
    }

    @JsonIgnore
    public String keyspace(Integer version) {
        return KIBANA_KEYSPACE_PREFIX + (Strings.isNullOrEmpty(name) ? "" : "-" + name) + (version == null ? "" : "_"+version);
    }

    @JsonIgnore
    public String role() {
        return KIBANA_PREFIX + (Strings.isNullOrEmpty(name) ? "" : "-" + name);
    }

    @JsonIgnore
    public String name() {
        return KIBANA_PREFIX + (Strings.isNullOrEmpty(name) ? "" : "-" + name);
    }

    @JsonIgnore
    public List<String> statements() {
        return statements == null || statements.isEmpty()
                ? ImmutableList.of(
                    String.format(Locale.ROOT, "GRANT ALL PERMISSIONS ON KEYSPACE \"%s\" TO %s", keyspace(version), role()),
                    String.format(Locale.ROOT, "INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','cluster:monitor/.*','.*')", role()),
                    String.format(Locale.ROOT, "INSERT INTO elastic_admin.privileges (role,actions,indices) VALUES ('%s','indices:.*','.*')", role()))
                : statements;
    }

    @JsonIgnore
    public String fingerprint(String image) {
        List<Object> acc = new ArrayList<>();

        acc.add(image);
        acc.add(ingressAnnotations);
        acc.add(ingressSuffix);
        acc.add(podTemplate);

        String json = GsonUtils.toJson(acc);
        String digest = DigestUtils.sha1Hex(json).substring(0,7);
        return digest;
    }
}
