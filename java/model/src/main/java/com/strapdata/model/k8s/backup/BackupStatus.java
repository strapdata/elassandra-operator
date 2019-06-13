
package com.strapdata.model.k8s.backup;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class BackupStatus {

    @SerializedName("progress")
    @Expose
    private String progress;
}
