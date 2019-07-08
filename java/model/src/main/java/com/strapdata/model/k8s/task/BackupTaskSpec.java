package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1LabelSelector;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BackupTaskSpec extends TaskSpec {
    
    @SerializedName("selector")
    @Expose
    private V1LabelSelector selector;
    @SerializedName("backupType")
    @Expose
    private String backupType;
    @SerializedName("target")
    @Expose
    private String target;
}
