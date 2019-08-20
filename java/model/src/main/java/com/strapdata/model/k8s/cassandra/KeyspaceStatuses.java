package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class KeyspaceStatuses {

    @SerializedName("observedReplicas")
    @Expose
    private Integer observedReplicas;
}
