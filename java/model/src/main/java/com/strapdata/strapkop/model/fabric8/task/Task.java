package com.strapdata.strapkop.model.fabric8.task;

import io.fabric8.kubernetes.client.CustomResource;
import lombok.*;

// see https://github.com/fabric8io/kubernetes-client/tree/master/kubernetes-examples/src/main/java/io/fabric8/kubernetes/examples/crds
@ToString
@Getter
@Setter
@With
@AllArgsConstructor
@NoArgsConstructor
public class Task extends CustomResource {
    private TaskSpec spec;
    private TaskStatus status;
}
