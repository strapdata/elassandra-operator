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

package com.strapdata.strapkop.model.k8s.datacenter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import io.kubernetes.client.openapi.models.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@Schema(name="DataCenterSpec", description="Elassandra Datacenter specification")
public class DataCenterSpec {

    @JsonPropertyDescription("Cassandra cluster name")
    @SerializedName("clusterName")
    @Expose
    @Schema(description = "Elassandra cluster name", minLength = 2, maxLength = 16, required = true)
    private String clusterName;

    @JsonPropertyDescription("Cassandra datacenter name")
    @SerializedName("datacenterName")
    @Expose
    private String datacenterName;

    @JsonPropertyDescription("Number of Cassandra nodes in this datacenter")
    @SerializedName("replicas")
    @Expose
    private int replicas;

    @JsonPropertyDescription("Park the datacenter by setting sts to zero replica, but keep PVC and replica unchanged.")
    @SerializedName("parked")
    @Expose
    private boolean parked = false;

    /**
     * How the operator decide to spawn a new E* node
     * MANUAL : based on the rplicas value
     * N : based on the Number of nodes
     */
    @JsonPropertyDescription("How the operator decide to spawn a new Elassandra node")
    @SerializedName("autoScaleMode")
    @Expose
    private AutoScaleMode autoScaleMode = AutoScaleMode.MANUAL;

    @JsonPropertyDescription("Elassandra pods affinity policy with respect to the failure-domain.beta.kubernetes.io/zone label")
    @SerializedName("podAffinityPolicy")
    @Expose
    private PodsAffinityPolicy podsAffinityPolicy = PodsAffinityPolicy.STRICT;

    @JsonPropertyDescription("Elassandra image")
    @SerializedName("elassandraImage")
    @Expose
    private java.lang.String elassandraImage;

    @JsonPropertyDescription("PodDisruptionBudget max unavailable Elassandra pod")
    @SerializedName("maxPodUnavailable")
    @Expose
    private Integer maxPodUnavailable = 1;

    /**
     * PodTemplate provides pod customisation (labels, resource, annotations, affinity rules, resource, priorityClassName, serviceAccountName) for the elassandra pods
     */
    @JsonPropertyDescription("Elassandra pods template allowing customisation")
    @SerializedName("podTemplate")
    @Expose
    private V1PodTemplateSpec podTemplate = new V1PodTemplateSpec();

    /**
     * PVC spec
     */
    @JsonPropertyDescription("Elassandra dataVolumeClaim")
    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;

    /**
     * Decomission policy control PVC when node removed.
     */
    @JsonPropertyDescription("Decomission policy control PVC when node removed")
    @SerializedName("decommissionPolicy")
    @Expose
    private DecommissionPolicy decommissionPolicy = DecommissionPolicy.DELETE_PVC;

    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     *
     */
    @JsonPropertyDescription("Name of an optional config map that contains cassandra configuration in the form of yaml fragments")
    @SerializedName("userConfigMapVolumeSource")
    @Expose
    private V1ConfigMapVolumeSource userConfigMapVolumeSource = null;

    /**
     * Name of an optional secret that contains cassandra related secrets
     *
     */
    @JsonPropertyDescription("Name of an optional secret that contains cassandra related secrets")
    @SerializedName("userSecretVolumeSource")
    @Expose
    private V1SecretVolumeSource userSecretVolumeSource;

    /**
     * Prometheus configuration.
     */
    @JsonPropertyDescription("Prometheus configuration")
    @SerializedName("prometheus")
    @Expose
    private Prometheus prometheus = new Prometheus();

    /**
     * Kubernetes networking configuration
     */
    @JsonPropertyDescription("Networking configuration")
    @SerializedName("networking")
    @Expose
    private Networking networking = new Networking();

    /**
     * Jvm configuration
     */
    @JsonPropertyDescription("JVM configuration")
    @SerializedName("jvm")
    @Expose
    private Jvm jvm = new Jvm();

    /**
     * Cassandra configuration
     */
    @JsonPropertyDescription("Cassandra configuration")
    @SerializedName("cassandra")
    @Expose
    private Cassandra cassandra = new Cassandra();

    /**
     * Reaper configuration.
     *
     */
    @JsonPropertyDescription("Cassandra reaper configuration")
    @SerializedName("reaper")
    @Expose
    private Reaper reaper = new Reaper();

    /**
     * Managed keyspaces map.
     */
    @JsonPropertyDescription("Managed keyspaces configuration")
    @SerializedName("managedKeyspaces")
    @Expose
    private Set<ManagedKeyspace> managedKeyspaces = new HashSet<>();

    /**
     * Elasticsearch configuration
     */
    @JsonPropertyDescription("Elasticsearch configuration")
    @SerializedName("elasticsearch")
    @Expose
    private Elasticsearch elasticsearch = new Elasticsearch();

    /**
     * Kibana configuration.
     *
     */
    @JsonPropertyDescription("Kibana configuration")
    @SerializedName("kibana")
    @Expose
    private Kibana kibana = new Kibana();

    /**
     * Elassandra webhook URL called when the datacenter is reconcilied.
     */
    @JsonPropertyDescription("Elassandra webhook URL called when the datacenter is reconcilied")
    @SerializedName("webHookUrl")
    @Expose
    private String webHookUrl = null;

    /**
     * Definition of Scheduled Backups.
     */
    @JsonPropertyDescription("Definition of Scheduled Backups")
    @SerializedName("scheduledBackups")
    @Expose
    private List<ScheduledBackup> scheduledBackups = new ArrayList<>();

    public String elassandraFingerprint() {
        List<Object> acc = new ArrayList<>();

        // we exclude :
        // * Reaper config
        // * Kibana config
        // * parked attribute
        // * scheduledBackups (DC reconciliation is useless in this case, we only want to update Scheduler)
        acc.add(podsAffinityPolicy);
        acc.add(elassandraImage);
        acc.add(webHookUrl);
        acc.add(podTemplate);
        acc.add(networking);
        acc.add(cassandra);
        acc.add(elasticsearch);
        acc.add(prometheus);
        acc.add(jvm);
        acc.add(cassandra);
        acc.add(userSecretVolumeSource);
        if (userConfigMapVolumeSource != null) {
            acc.add(userConfigMapVolumeSource);
        }

        String json = GsonUtils.toJson(acc);
        String digest = DigestUtils.sha1Hex(json).substring(0,7);
        ///System.out.println(json+"="+digest);
        return digest;
    }
}
