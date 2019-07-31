package com.strapdata.model.k8s.task;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1ObjectMeta;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Task {
    
    @SerializedName("apiVersion")
    @Expose
    private String apiVersion = "stable.strapdata.com/v1";
    @SerializedName("kind")
    @Expose
    private String kind = "ElassandraTask";
    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;
    @SerializedName("spec")
    @Expose
    private TaskSpec spec;
    @SerializedName("status")
    @Expose
    private TaskStatus status;
    
    public static Task fromDataCenter(String name, DataCenter dc) {
        return new Task()
                .setMetadata(new V1ObjectMeta().name(name).namespace(dc.getMetadata().getNamespace()))
                .setSpec(new TaskSpec()
                        .setCluster(dc.getSpec().getClusterName())
                        .setDatacenter(dc.getSpec().getDatacenterName())
                );
    }
}
