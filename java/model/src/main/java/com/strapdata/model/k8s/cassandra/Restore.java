package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.task.BackupTaskSpec;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Restore extends BackupTaskSpec {

    @SerializedName("tag")
    @Expose
    private String snapshotTag;

}
