package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.backup.StorageProvider;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Restore {

    @SerializedName("tag")
    @Expose
    private String snapshotTag;

    @SerializedName("provider")
    @Expose
    private StorageProvider provider;

    @SerializedName("bucket")
    @Expose
    private String bucket;
}
