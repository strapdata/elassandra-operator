package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class ScheduledBackup {

    @SerializedName("tagSuffix")
    @Expose
    private String tagSuffix;

    @SerializedName("cron")
    @Expose
    private String cron;

    @SerializedName("backup")
    @Expose
    private BackupTaskSpec backup;

    private String fingerprint() {
        List<Object> acc = new ArrayList<>();
        acc.add(tagSuffix);
        acc.add(cron);
        if (backup != null) {
            acc.add(backup);
        }
        return DigestUtils.sha1Hex(GsonUtils.toJson(acc)).substring(0,7);
    }

    /**
     * Task name represents the snapshot/backup tag
     * for scheduled backup the default name is the backup timestamp
     * if a tagSuffix is present it is appended to the timestamp.
     *
     * NOTE : In any case, we append also the ScheduleBackup fingerprint to avoid timestamp collision between two backups
     * @return
     */
    public String computeTaskName() {
        return  Optional.ofNullable(tagSuffix)
                .map(suffix ->System.currentTimeMillis() + "-" + fingerprint() + "-" + suffix)
                .orElse(System.currentTimeMillis() + "-" + fingerprint())
                .toLowerCase();
    }
}