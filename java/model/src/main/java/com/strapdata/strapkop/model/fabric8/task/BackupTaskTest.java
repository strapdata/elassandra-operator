package com.strapdata.strapkop.model.fabric8.task;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.*;

import java.util.List;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@With
public class BackupTaskTest implements KubernetesResource {
    private String bucket;
    private String secretRef;
    private String keyspaceRegex;
    private List<String> keyspaces;
}
