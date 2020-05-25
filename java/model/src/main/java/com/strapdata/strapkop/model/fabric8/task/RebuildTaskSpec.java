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
public class RebuildTaskSpec implements KubernetesResource {
    private String srcDcName;
    private String keyspace;
}
