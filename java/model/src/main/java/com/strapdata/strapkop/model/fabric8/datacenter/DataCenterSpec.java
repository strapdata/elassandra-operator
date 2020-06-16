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
import io.kubernetes.client.openapi.models.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;
import java.util.Set;

@JsonDeserialize(using = JsonDeserializer.None.class)
@Getter
@Setter
@NoArgsConstructor
@ToString
public class DataCenterSpec implements KubernetesResource {
    private String clusterName;
    private String datacenterName;
    private Workload workload;
    private int replicas;
    private boolean parked;
    private AutoScaleMode autoScaleMode;
    private PodsAffinityPolicy podsAffinityPolicy;
    private String elassandraImage;
    private String imagePullPolicy;
    private List<String> imagePullSecrets;
    private String appServiceAccount;
    private String priorityClassName;
    private Map<String, String> annotations;
    private Map<String, String> customLabels;
    private List<V1EnvVar> env;
    private V1ResourceRequirements resources;
    private boolean computeJvmMemorySettings;
    private Boolean snitchPreferLocal;
    private V1PersistentVolumeClaimSpec dataVolumeClaim;
    private V1ConfigMapVolumeSource userConfigMapVolumeSource;
    private V1SecretVolumeSource userSecretVolumeSource;
    private Boolean prometheusEnabled;
    private Integer prometheusPort;
    private Reaper reaper;
    private Kibana kibana;
    private Set<ManagedKeyspace> managedKeyspaces;
    private Boolean privilegedSupported;
    private Boolean hostPortEnabled;
    private Boolean hostNetworkEnabled;
    private Boolean nodeLoadBalancerEnabled;
    private Boolean elasticsearchEnabled;
    private Integer elasticsearchPort;
    private Integer elasticsearchTransportPort;
    private Integer nativePort;
    private Integer storagePort;
    private Integer sslStoragePort;
    private Integer jmxPort;
    private Boolean jmxmpEnabled;
    private Boolean jmxmpOverSSL;
    private Integer jdbPort;
    private Boolean ssl;
    private ExternalDns externalDns;
    private DecommissionPolicy decommissionPolicy;
    private Authentication authentication;
    private Enterprise enterprise;
    private List<String> remoteSeeds;
    private List<String> remoteSeeders;
    private Boolean elasticsearchLoadBalancerEnabled;
    private String elasticsearchLoadBalancerIp;
    private Boolean elasticsearchIngressEnabled;
    private String datacenterGroup;
    private String webHookUrl;
    private List<ScheduledBackup> scheduledBackups;
}
