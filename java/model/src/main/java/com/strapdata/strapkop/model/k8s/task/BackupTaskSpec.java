package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Wither;

import java.util.List;

@Data
@Wither
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
