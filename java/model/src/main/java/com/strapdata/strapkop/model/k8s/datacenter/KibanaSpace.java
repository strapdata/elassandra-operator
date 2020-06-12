package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.openapi.models.V1PodTemplateSpec;
import lombok.*;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * Number of kibana instance, default is 1
     */
    @JsonPropertyDescription("Number of kibana instance, default is 1")
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 1;

    /**
     * Kibana ingress suffix (concatened with kibana spaces).
     * host: space-suffix
     */
    @JsonPropertyDescription("Kibana space ingress suffix")
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
    @JsonPropertyDescription("Kibana space upgrade version")
    @SerializedName("version")
    @Expose
    private Integer version = null;

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
        return KIBANA_INDEX_PREFIX + (name.length() > 0 ? "-" : "") + name + (version == null ? "" : "_"+version);
    }

    @JsonIgnore
    public String keyspace(Integer version) {
        return KIBANA_KEYSPACE_PREFIX + (name.length() > 0 ? "-" : "") + name + (version == null ? "" : "_"+version);
    }

    @JsonIgnore
    public String role() {
        return KIBANA_PREFIX + (name.length() > 0 ? "-" : "") + name;
    }

    @JsonIgnore
    public String name() {
        return KIBANA_PREFIX + (name.length() > 0 ? "-" : "") + name;
    }

}
