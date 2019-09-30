package com.strapdata.model.k8s.cassandra;

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

}
