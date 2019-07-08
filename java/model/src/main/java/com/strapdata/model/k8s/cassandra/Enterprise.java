
package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Enterprise {

    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    @SerializedName("jmx")
    @Expose
    private Boolean jmx = true;

    @SerializedName("https")
    @Expose
    private Boolean https = true;

    @SerializedName("ssl")
    @Expose
    private Boolean ssl = true;
    
    @SerializedName("aaa")
    @Expose
    private Aaa aaa;

    @SerializedName("cbs")
    @Expose
    private Boolean cbs = true;
}
