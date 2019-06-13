
package com.strapdata.model.k8s.backup;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ObjectMeta;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Backup {

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion;
    @SerializedName("kind")
    @Expose
    private String kind;
    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;
    @SerializedName("spec")
    @Expose
    private BackupSpec spec;
    @SerializedName("status")
    @Expose
    private BackupStatus status;
}
