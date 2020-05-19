package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.HashSet;
import java.util.Set;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class KeyspaceManagerStatus {

    /**
     * KeyspaceManager last update replicas count.
     */
    @SerializedName("replicas")
    private Integer replicas = 0;

    /**
     * Managed keyspaces with RF > 0
     */
    @SerializedName("keyspaces")
    private Set<String> keyspaces = new HashSet<>();
}
