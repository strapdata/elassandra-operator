
package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class DataCenter {

    public static final String NAME = "elassandradatacenter";
    public static final String PLURAL = "elassandradatacenters";
    public static final String VERSION = "v1";
    public static final String SCOPE = "Namespaced";
    public static final String KIND = "ElassandraDatacenter";

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion = StrapdataCrdGroup.GROUP + "/" + VERSION;

    @SerializedName("kind")
    @Expose
    private String kind = KIND;

    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    @Expose
    private DataCenterSpec spec;

    @SerializedName("status")
    @Expose
    private DataCenterStatus status = new DataCenterStatus();

    public String id() {
        return metadata.getName()+"/"+metadata.getNamespace();
    }
}
