package com.strapdata.strapkop.model.k8s.dnsendpoint;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class DnsEndpoint {
    public static final String GROUP = "externaldns.k8s.io";
    public static final String NAME = "dnsendpoint";
    public static final String PLURAL = "dnsendpoints";
    public static final String VERSION = "v1alpha1";
    public static final String SCOPE = "Namespaced";
    public static final String KIND = "DNSEndpoint";

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion =  GROUP + "/" + VERSION;

    @SerializedName("kind")
    @Expose
    private String kind = KIND;

    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    @Expose
    private DnsEndpointSpec spec;

    @SerializedName("status")
    @Expose
    private DnsEndpointStatus status = new DnsEndpointStatus();

    public String id() {
        return metadata.getNamespace() + "/" + metadata.getName();
    }
}
