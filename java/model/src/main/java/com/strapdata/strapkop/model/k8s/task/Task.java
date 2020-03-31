package com.strapdata.strapkop.model.k8s.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.models.V1ObjectMeta;
import lombok.*;
import lombok.experimental.Wither;

import java.util.Date;

@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
public class Task {

    public static final String NAME = "elassandratask";
    public static final String PLURAL = "elassandratasks";
    public static final String VERSION = "v1";
    public static final String SCOPE = "Namespaced";
    public static final String KIND = "ElassandraTask";

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion = StrapdataCrdGroup.GROUP + "/" + VERSION;

    @SerializedName("kind")
    @Expose
    private String kind = KIND;

    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    @Expose
    private TaskSpec spec;

    @SerializedName("status")
    @Expose
    private TaskStatus status = new TaskStatus().withStartDate(new Date()).setPhase(TaskPhase.WAITING);
    
    public static Task fromDataCenter(String name, DataCenter dc) {
        return new Task()
                .setMetadata(new V1ObjectMeta().name(name)
                        .namespace(dc.getMetadata().getNamespace())
                        .labels(OperatorLabels.datacenter(dc))
                )
                .setSpec(new TaskSpec()
                        .setCluster(dc.getSpec().getClusterName())
                        .setDatacenter(dc.getSpec().getDatacenterName())
                );
    }

    public String id() {
        return metadata.getName()+"/"+metadata.getNamespace();
    }

    @JsonIgnore
    public String getParent() {
        return getMetadata().getLabels().get(OperatorLabels.PARENT);
    }

}
