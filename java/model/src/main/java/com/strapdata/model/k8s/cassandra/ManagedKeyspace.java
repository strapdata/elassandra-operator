package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kibana deployment context.
 * A kibana password is generated as a k8s secret if not exists, and a C* role is created if not exists with this password.
 */
@Data
@NoArgsConstructor
public class ManagedKeyspace {

    /**
     * Keyspace name
     */
    @SerializedName("name")
    @Expose
    private String name;

    /**
     * Target replication factor
     */
    @SerializedName("rf")
    @Expose
    private Integer rf = 1;

    /**
     * CQL Role name, may be null
     */
    @SerializedName("role")
    @Expose
    private String role;

    /**
     * CQL role is superuser, default is false
     */
    @SerializedName("superuser")
    @Expose
    private Boolean superuser = false;

    /**
     * CQL role is authorized to login, default is true
     */
    @SerializedName("login")
    @Expose
    private Boolean login = true;

    /**
     * K8s secret name for the role password.
     */
    @SerializedName("secretName")
    @Expose
    private String secretName;

    /**
     * K8s secret key for the role password
     */
    @SerializedName("secretKey")
    @Expose
    private String secretKey;

    /**
     * CQL grant statements
     */
    @SerializedName("grantStatements")
    @Expose
    private List<String> grantStatements = new ArrayList<>();

}
