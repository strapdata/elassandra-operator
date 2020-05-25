package com.strapdata.strapkop.model.fabric8.task;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@ToString
public class RepairTaskSpec implements KubernetesResource {
    String keyspace;
}
