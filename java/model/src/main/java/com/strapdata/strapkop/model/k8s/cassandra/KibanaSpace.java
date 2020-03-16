package com.strapdata.strapkop.model.k8s.cassandra;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.HashSet;
import java.util.Set;

/**
 * Kibana deployment context.
 * A kibana password is generated as a k8s secret if not exists, and a C* role is created if not exists with this password.
 */
@Data
@Wither
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
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Visible keyspaces
     */
    @SerializedName("keyspaces")
    @Expose
    private Set<String> keyspaces = new HashSet<>();

    /**
     * Number of kibana instance, default is 1
     */
    @SerializedName("replicas")
    @Expose
    private Integer replicas = 1;

    /**
     * Kibana nodeJS options, NODE_OPTIONS="--max-old-space-size=4096"
     */
    @SerializedName("nodeOptions")
    @Expose
    private String nodeOptions;


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
