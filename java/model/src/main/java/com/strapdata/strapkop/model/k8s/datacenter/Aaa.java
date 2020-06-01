
package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Aaa {

    /**
     * Enable Elasticsearch authentication
     */
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    /**
     * Enable Elasticsearch audit
     */
    @SerializedName("audit")
    @Expose
    private Boolean audit = true;
}