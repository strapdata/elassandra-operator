package com.strapdata.strapkop.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ListMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.ArrayList;
import java.util.List;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class TaskList{

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion;

    @SerializedName("kind")
    @Expose
    private String kind;

    @SerializedName("metadata")
    @Expose
    private V1ListMeta metadata;

    @SerializedName("items")
    @Expose
    private List<Task> items = new ArrayList<>();
}