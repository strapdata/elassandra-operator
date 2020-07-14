package com.strapdata.strapkop.model.k8s.dnsendpoint;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.Map;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Endpoint {
    @SerializedName("dnsName")
    @Expose
    String dnsName;

    @SerializedName("labels")
    @Expose
    Map<String, String> labels;

    @SerializedName("recordTTL")
    @Expose
    Long recordTTL;

    @SerializedName("recordType")
    @Expose
    String recordType;

    @SerializedName("targets")
    @Expose
    String[] targets;

    @SerializedName("providerSpecific")
    @Expose
    ProviderSpecificProperty[] providerSpecific;
}
