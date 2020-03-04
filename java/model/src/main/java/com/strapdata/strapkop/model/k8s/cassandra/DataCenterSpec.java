
package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.GsonUtils;
import io.kubernetes.client.models.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class DataCenterSpec {

    @SerializedName("clusterName")
    @Expose
    private String clusterName;

    @SerializedName("datacenterName")
    @Expose
    private String datacenterName;

    @SerializedName("workload")
    @Expose
    private Workload workload = Workload.WRITE;

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

    @SerializedName("computeJvmMemorySettings")
    @Expose
    private boolean computeJvmMemorySettings = true;

    /**
     *  Tell Cassandra to use the local IP address (INTERNAL_IP).
     *  May require a VPC  or VPN between the datacenters.
     */
    @SerializedName("snitchPreferLocal")
    @Expose
    private Boolean snitchPreferLocal = true;

    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;

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
     * Enable Prometheus support.
     */
    @SerializedName("prometheusEnabled")
    @Expose
    private Boolean prometheusEnabled = false;

    /**
     * Prometheus agent listen port.
     */
    @SerializedName("prometheusPort")
    @Expose
    private Integer prometheusPort = 9500;

    /**
     * Reaper configuration.
     *
     */
    @SerializedName("reaper")
    @Expose
    private Reaper reaper = new Reaper();

    /**
     * Kibana configuration.
     *
     */
    @SerializedName("kibana")
    @Expose
    private Kibana kibana = new Kibana();

    /**
     * Managed keyspaces map.
     */
    @SerializedName("managedKeyspaces")
    @Expose
    private Set<ManagedKeyspace> managedKeyspaces = new HashSet<>();

    /**
     * Attempt to run privileged configuration options for better performance
     *
     */
    @SerializedName("privilegedSupported")
    @Expose
    private Boolean privilegedSupported = false;

    /**
     * Enable hostPort for nativePort, storagePort and sslStoragePort
     */
    @SerializedName("hostPortEnabled")
    @Expose
    private Boolean hostPortEnabled = true;

    /**
     * Enable hostNetwork, allowing to bind on host IP addresses.
     */
    @SerializedName("hostNetworkEnabled")
    @Expose
    private Boolean hostNetworkEnabled = false;


    /**
     * Enable elasticsearch service
     */
    @SerializedName("elasticsearchEnabled")
    @Expose
    private Boolean elasticsearchEnabled = true;

    /**
     * Elasticsearch HTTP port
     */
    @SerializedName("elasticsearchPort")
    @Expose
    private Integer elasticsearchPort = 9200;

    /**
     * Elasticsearch Transport port
     */
    @SerializedName("elasticsearchTransportPort")
    @Expose
    private Integer elasticsearchTransportPort = 9300;

    /**
     * CQL native port (also hostPort)
     */
    @SerializedName("nativePort")
    @Expose
    private Integer nativePort = 39042;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("storagePort")
    @Expose
    private Integer storagePort = 37000;

    /**
     * Cassandra storage port (also hostPort)
     */
    @SerializedName("sslStoragePort")
    @Expose
    private Integer sslStoragePort = 37001;

    /**
     * Java JMX port
     */
    @SerializedName("jmxPort")
    @Expose
    private Integer jmxPort = 7199;

    /**
     * Enable JMXMP.
     */
    @SerializedName("jmxmpEnabled")
    @Expose
    private Boolean jmxmpEnabled = true;

    /**
     * Enable SSL with JMXMP.
     */
    @SerializedName("jmxmpOverSSL")
    @Expose
    private Boolean jmxmpOverSSL = true;

    /**
     * Java debugger port (also hostPort)
     */
    @SerializedName("jdbPort")
    @Expose
    private Integer jdbPort = -1;

    /**
     * Enable SSL support
     */
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;

    /**
     * External DNS config for public nodes and elasticsearch service.
     */
    @SerializedName("externalDns")
    @Expose
    private ExternalDns externalDns = null;

    /**
     * Decomission policy control PVC when node removed.
     */
    @SerializedName("decommissionPolicy")
    @Expose
    private DecommissionPolicy decommissionPolicy = DecommissionPolicy.DELETE_PVC;

    /**
     * Enable cassandra/ldap authentication and authorization
     */
    @SerializedName("authentication")
    @Expose
    private Authentication authentication = Authentication.CASSANDRA;

    @SerializedName("enterprise")
    @Expose
    private Enterprise enterprise = new Enterprise();

    /**
     * Remote seed IP addresses.
     */
    @SerializedName("remoteSeeds")
    @Expose
    private List<String> remoteSeeds = new ArrayList<>();

    /**
     * List of URL providing dynamic seed list.
     */
    @SerializedName("remoteSeeders")
    @Expose
    private List<String> remoteSeeders = new ArrayList<>();

    /**
     * Create a Load balancer service with external IP for Elasticsearch
     */
    @SerializedName("elasticsearchLoadBalancerEnabled")
    @Expose
    private Boolean elasticsearchLoadBalancerEnabled = false;

    /**
     * The LoadBalancer exposing cql + elasticsearch nodePorts
     */
    @SerializedName("elasticsearchLoadBalancerIp")
    @Expose
    private String elasticsearchLoadBalancerIp;

    /**
     * Enable Elasticsearch service ingress
     */
    @SerializedName("elasticsearchIngressEnabled")
    @Expose
    private Boolean elasticsearchIngressEnabled = false;

    /**
     * Elassandra datacenter group
     */
    @SerializedName("datacenterGroup")
    @Expose
    private String datacenterGroup = null;

    /**
     * Elassandra web hook URL called when the datacenter is reconcilied.
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

    public String fingerprint() {
        List<Object> acc = new ArrayList<>();

        // we exclude :
        // * Reaper config
        // * Kibana config
        // * parked attribute
        // * scheduledBackups (DC reconciliation is useless in this case, we only want to update Scheduler)

        acc.add(workload);
        acc.add(podsAffinityPolicy);
        acc.add(elassandraImage);
        acc.add(imagePullPolicy);
        acc.add(imagePullSecrets);
        acc.add(env);
        acc.add(resources);
        acc.add(prometheusEnabled);
        acc.add(elasticsearchEnabled);
        acc.add(elasticsearchPort);
        acc.add(privilegedSupported);
        acc.add(hostNetworkEnabled);
        acc.add(hostPortEnabled);
        acc.add(nativePort);
        acc.add(storagePort);
        acc.add(sslStoragePort);
        acc.add(jmxPort);
        acc.add(jmxmpOverSSL);
        acc.add(jmxmpEnabled);
        acc.add(jdbPort);
        acc.add(ssl);
        acc.add(decommissionPolicy);
        acc.add(authentication);
        acc.add(enterprise);
        acc.add(remoteSeeders);
        acc.add(remoteSeeds);
        acc.add(datacenterGroup);
        acc.add(userSecretVolumeSource);
        if (userConfigMapVolumeSource != null) {
            acc.add(userConfigMapVolumeSource);
        }
        return DigestUtils.sha1Hex(GsonUtils.toJson(acc)).substring(0,7);
    }
}
