package com.strapdata.strapkop.model.k8s.dnsendpoint;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
public class DnsEndpointStatus {

    @JsonPropertyDescription("Last observed DNSEndpoint spec generation")
    @SerializedName("observedGeneration")
    @Expose
    Long observedGeneration;
}
