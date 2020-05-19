package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.*;
import java.util.List;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class BackupTaskSpec  {
    @SerializedName("repository")
    @Expose
    private String repository;

    @SerializedName("keyspaceRegex")
    @Expose
    private String keyspaceRegex;

    @SerializedName("keyspaces")
    @Expose
    private List<String> keyspaces;
}
