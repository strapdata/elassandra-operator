
package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Aaa {
    
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;
    @SerializedName("audit")
    @Expose
    private Boolean audit = false;
    @SerializedName("sharedSecret")
    @Expose
    private String sharedSecret;
}
