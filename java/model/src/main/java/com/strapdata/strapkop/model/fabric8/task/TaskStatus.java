package com.strapdata.strapkop.model.fabric8.task;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@ToString
public class TaskStatus implements KubernetesResource {
    private TaskPhase phase = TaskPhase.WAITING;
    private Date startDate;
    private Long durationInMs;
    private String lastMessage = null;
    private Map<String, TaskPhase> pods = new HashMap<>();
}
