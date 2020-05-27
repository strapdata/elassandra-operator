
package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import io.kubernetes.client.openapi.models.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.*;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class DataCenterSpec {

    @SerializedName("clusterName")
    @Expose
    private String clusterName;

    @SerializedName("datacenterName")
    @Expose
    private String datacenterName;

    /**
     * Number of Cassandra nodes in this data center.
     */
    @SerializedName("replicas")
    @Expose
    private int replicas;

    /**
     * Park the datacenter by setting sts to zero replica, but keep PVC and replica unchanged.
     */
    @SerializedName("parked")
    @Expose
    private boolean parked = false;

    /**
     * How the operator decide to spawn a new E* node
     * MANUAL : based on the rplicas value
     * N - 1 : based on the Number of nodes minus one (with min to 1)
     * N : based on the Number of nodes
     */
    @SerializedName("autoScaleMode")
    @Expose
    private AutoScaleMode autoScaleMode = AutoScaleMode.MANUAL;

    @SerializedName("podAffinityPolicy")
    @Expose
    private PodsAffinityPolicy podsAffinityPolicy = PodsAffinityPolicy.STRICT;

    @SerializedName("elassandraImage")
    @Expose
    private java.lang.String elassandraImage;

    @SerializedName("imagePullPolicy")
    @Expose
    private java.lang.String imagePullPolicy;

    @SerializedName("imagePullSecrets")
    @Expose
    private List<java.lang.String> imagePullSecrets;

    /**
     * ServiceAccount used by the operator to deploy pods (Elassandra, Reaper, kibana...)
     */
    @SerializedName("appServiceAccount")
    @Expose
    private String appServiceAccount;

    /**
     * Elassandra pods priorityClassName
     */
    @SerializedName("priorityClassName")
    @Expose
    private String priorityClassName;

    /**
     * Elassandra additional annotations
     */
    @SerializedName("annotations")
    @Expose
    private Map<String, String> annotations;

    /**
     * Elassandra custom Labels
     */
    @SerializedName("customLabels")
    @Expose
    private Map<String, String> customLabels;

    /**
     * List of environment variables to inject in the Cassandra & Sidecar container.
     *
     */
    @SerializedName("env")
    @Expose
    private List<V1EnvVar> env = new ArrayList<>();

    /**
     * Resource requirements for the Cassandra container.
     *
     */
    @SerializedName("resources")
    @Expose
    private V1ResourceRequirements resources = null;

    /**
     * PVC spec
     */
    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;


    /**
     * Decomission policy control PVC when node removed.
     */
    @SerializedName("decommissionPolicy")
    @Expose
    private DecommissionPolicy decommissionPolicy = DecommissionPolicy.DELETE_PVC;

    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     *
     */
    @SerializedName("userConfigMapVolumeSource")
    @Expose
    private V1ConfigMapVolumeSource userConfigMapVolumeSource = null;

    /**
     * Name of an optional secret that contains cassandra related secrets
     *
     */
    @SerializedName("userSecretVolumeSource")
    @Expose
    private V1SecretVolumeSource userSecretVolumeSource;

    /**
     * Prometheus configuration.
     */
    @SerializedName("prometheus")
    @Expose
    private Prometheus prometheus = new Prometheus();

    /**
     * Reaper configuration.
     *
     */
    @SerializedName("reaper")
    @Expose
    private Reaper reaper = new Reaper();

    /**
     * Managed keyspaces map.
     */
    @SerializedName("managedKeyspaces")
    @Expose
    private Set<ManagedKeyspace> managedKeyspaces = new HashSet<>();

    /**
     * Kubernetes networking configuration
     */
    @SerializedName("networking")
    @Expose
    private Networking networking = new Networking();

    /**
     * Elasticsearch configuration
     */
    @SerializedName("elasticsearch")
    @Expose
    private Elasticsearch elasticsearch = new Elasticsearch();

    /**
     * External DNS config for public nodes and elasticsearch service.
     */
    @SerializedName("externalDns")
    @Expose
    private ExternalDns externalDns = null;


    /**
     * Jvm configuration
     */
    @SerializedName("jvm")
    @Expose
    private Jvm jvm = new Jvm();

    /**
     * Cassandra configuration
     */
    @SerializedName("cassandra")
    @Expose
    private Cassandra cassandra = new Cassandra();

    /**
     * Elassandra webhook URL called when the datacenter is reconcilied.
     */
    @SerializedName("webHookUrl")
    @Expose
    private String webHookUrl = null;

    /**
     * Definition of Scheduled Backups.
     */
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
        acc.add(imagePullPolicy);
        acc.add(imagePullSecrets);
        acc.add(webHookUrl);
        acc.add(env);
        acc.add(annotations);
        acc.add(customLabels);
        acc.add(priorityClassName);
        acc.add(appServiceAccount);
        acc.add(externalDns);
        acc.add(networking);
        acc.add(cassandra);
        acc.add(elasticsearch);
        acc.add(resources);
        acc.add(prometheus);
        acc.add(jvm);
        acc.add(managedKeyspaces);
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
