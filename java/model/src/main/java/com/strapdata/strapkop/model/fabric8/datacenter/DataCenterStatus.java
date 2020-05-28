package com.strapdata.strapkop.model.fabric8.datacenter;

import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.strapdata.strapkop.model.k8s.datacenter.*;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@ToString
public class DataCenterStatus implements KubernetesResource {
    Operation currentOperation;
    List<Operation> operationHistory;
    DataCenterPhase phase;
    private Health health;
    Boolean needCleanup;
    Set<String> needCleanupKeyspaces;
    Boolean bootstrapped;
    String lastError;
    Date lastErrorTime;
    CqlStatus cqlStatus;
    String cqlStatusMessage;
    String configMapFingerPrint;
    String currentTask;
    List<String> zones;
    Integer readyReplicas;
    Map<String, RackStatus> rackStatuses;
    KeyspaceManagerStatus keyspaceManagerStatus;
    Set<String> kibanaSpaceNames;
    ReaperPhase reaperPhase;
}
