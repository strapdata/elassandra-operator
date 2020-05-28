package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class Restore extends BackupTaskSpec {

    @SerializedName("tag")
    @Expose
    private String snapshotTag;

    @SerializedName("backupDir")
    @Expose
    private String backupDir;

    @SerializedName("namespace")
    @Expose
    private String namespace;
}
