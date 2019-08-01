package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1LabelSelector;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BackupTaskSpec {
    @SerializedName("type")
    @Expose
    private String type;
    @SerializedName("target")
    @Expose
    private String target;
}
