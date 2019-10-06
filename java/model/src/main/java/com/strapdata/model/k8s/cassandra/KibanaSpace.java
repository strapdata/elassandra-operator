package com.strapdata.model.k8s.cassandra;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Kibana deployment context.
 * A kibana password is generated as a k8s secret if not exists, and a C* role is created if not exists with this password.
 */
@Data
@NoArgsConstructor
public class KibanaSpace {

    public static final String KIBANA_INDEX_PREFIX = ".kibana";
    public static final String KIBANA_KEYSPACE_PREFIX = "_kibana";
    public static final String KIBANA_ROLE_PREFIX = "kibana";

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

    @JsonIgnore
    public String index() {
        return KIBANA_INDEX_PREFIX + (name.length() > 0 ? "-" : "") + name;
    }

    @JsonIgnore
    public String keyspace() {
        return KIBANA_KEYSPACE_PREFIX + (name.length() > 0 ? "-" : "") + name;
    }

    @JsonIgnore
    public String role() {
        return KIBANA_ROLE_PREFIX + (name.length() > 0 ? "-" : "") + name;
    }
}
