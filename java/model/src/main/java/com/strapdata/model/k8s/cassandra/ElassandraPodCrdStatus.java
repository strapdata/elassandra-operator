package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.sidecar.ElassandraPodStatus;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ElassandraPodCrdStatus {
    
    @SerializedName("podName")
    @Expose
    private String podName;
    
    @SerializedName("mode")
    @Expose
    private ElassandraPodStatus mode;
}
