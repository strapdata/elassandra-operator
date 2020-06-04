
package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Enterprise {

    @JsonPropertyDescription("Enable Elassandra Enterprise")
    @SerializedName("enabled")
    @Expose
    private Boolean enabled = true;

    @JsonPropertyDescription("Enable JMX for Elasticsearch metrics")
    @SerializedName("jmx")
    @Expose
    private Boolean jmx = true;

    @JsonPropertyDescription("Enable HTTPS for Elasticsearch")
    @SerializedName("https")
    @Expose
    private Boolean https = true;

    @JsonPropertyDescription("Enable TLS for Elasticsearch transport connections")
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = true;

    @JsonPropertyDescription("Enable Elasticsearch Authentication, Authorization and Accounting")
    @SerializedName("aaa")
    @Expose
    private Aaa aaa;

    @JsonPropertyDescription("Enable Elasticsearch Content-Based security")
    @SerializedName("cbs")
    @Expose
    private Boolean cbs = true;
}
