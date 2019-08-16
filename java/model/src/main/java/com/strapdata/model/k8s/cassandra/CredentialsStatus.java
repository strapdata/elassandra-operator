package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CredentialsStatus {
    
    @Expose
    @SerializedName("managed")
    private Boolean managed = false;

    @Expose
    @SerializedName("defaultRole")
    private Boolean defaultRole = true;
    
    @Expose
    @SerializedName("unknown")
    private Boolean unknown = false;
}
