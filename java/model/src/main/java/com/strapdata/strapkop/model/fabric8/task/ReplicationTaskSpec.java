package com.strapdata.strapkop.model.fabric8.task;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.*;

import java.util.Map;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@With
public class ReplicationTaskSpec implements KubernetesResource {
    com.strapdata.strapkop.model.k8s.task.ReplicationTaskSpec.Action action;
    String dcName;
    int dcSize;
    Map<String, Integer> replicationMap;
}
