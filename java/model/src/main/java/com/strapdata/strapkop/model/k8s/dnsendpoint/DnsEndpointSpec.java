package com.strapdata.strapkop.model.k8s.dnsendpoint;

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
public class DnsEndpointSpec {
    @SerializedName("endpoints")
    @Expose
    Endpoint[] endpoints;
}
