
package com.strapdata.model.k8s.cassandra;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.model.GsonUtils;
import io.kubernetes.client.models.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.LoggerFactory;

import java.util.*;

@Data
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

    @SerializedName("sidecarImage")
    @Expose
    private java.lang.String sidecarImage;

    @SerializedName("imagePullPolicy")
    @Expose
    private java.lang.String imagePullPolicy;

    @SerializedName("imagePullSecrets")
    @Expose
    private List<java.lang.String> imagePullSecrets;

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

    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;

    /**
     * Name of the CassandraBackup to restore from
     *
     */
    @SerializedName("restoreFromBackup")
    @Expose
    private Restore restoreFromBackup = new Restore();

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
     `     * `
     */
    @SerializedName("prometheusEnabled")
    @Expose
    private Boolean prometheusEnabled = false;


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
     * Attempt to run privileged configuration options for better performance
     *
     */
    @SerializedName("privilegedSupported")
    @Expose
    private Boolean privilegedSupported = false;

    /**
     * Enable elasticsearch service
     */
    @SerializedName("elasticsearchEnabled")
    @Expose
    private Boolean elasticsearchEnabled = true;

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
     * Elassandra datacenter group
     */
    @SerializedName("datacenterGroup")
    @Expose
    private String datacenterGroup = null;


    public String fingerprint() {
        List<Object> acc = new ArrayList<>();
        acc.add(workload);

        // we exclude Reaper & Kibana config
        acc.add(podsAffinityPolicy);
        acc.add(elassandraImage);
        acc.add(sidecarImage);
        acc.add(imagePullPolicy);
        acc.add(imagePullSecrets);
        acc.add(env);
        acc.add(resources);
        acc.add(prometheusEnabled);
        acc.add(elasticsearchEnabled);
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
        // TODO [ELE] fully copy the configmap to include the cofgimap into the dc fingerprint
        return DigestUtils.sha1Hex(GsonUtils.toJson(acc)).substring(0,7);
    }
}
