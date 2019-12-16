package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.backup.StorageProvider;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BackupTaskSpec  {
    @SerializedName("provider")
    @Expose
    private StorageProvider provider;
    @SerializedName("bucket")
    @Expose
    private String bucket;
    @SerializedName("secretRef")
    @Expose
    private String secretRef;
}
