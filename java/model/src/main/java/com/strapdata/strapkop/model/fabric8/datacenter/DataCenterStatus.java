/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

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
