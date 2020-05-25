package com.strapdata.strapkop.model.fabric8.task;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.*;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@With
public class RemoveNodesTaskSpec implements KubernetesResource {
    private String dcName;
}
