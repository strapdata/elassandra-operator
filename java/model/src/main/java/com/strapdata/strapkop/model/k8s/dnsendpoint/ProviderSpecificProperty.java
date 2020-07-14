package com.strapdata.strapkop.model.k8s.dnsendpoint;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class ProviderSpecificProperty {
    @SerializedName("name")
    @Expose
    String name;

    @SerializedName("value")
    @Expose
    String value;
}
