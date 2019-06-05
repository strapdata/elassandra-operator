
package com.instaclustr.model.k8s.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ConfigMapVolumeSource;
import io.kubernetes.client.models.V1EnvVar;
import io.kubernetes.client.models.V1PersistentVolumeClaimSpec;
import io.kubernetes.client.models.V1ResourceRequirements;
import io.kubernetes.client.models.V1SecretVolumeSource;

public class DataCenterSpec {

    /**
     * Number of Cassandra nodes in this data center.
     * 
     */
    @SerializedName("replicas")
    @Expose
    private int replicas;
    @SerializedName("elassandraImage")
    @Expose
    private java.lang.String elassandraImage;
    @SerializedName("enterpriseImage")
    @Expose
    private java.lang.String enterpriseImage;
    @SerializedName("sidecarImage")
    @Expose
    private java.lang.String sidecarImage;
    @SerializedName("imagePullPolicy")
    @Expose
    private java.lang.String imagePullPolicy;
    @SerializedName("imagePullSecret")
    @Expose
    private java.lang.String imagePullSecret;
    /**
     * List of environment variables to inject in the Cassandra & Sidecar container.
     * 
     */
    @SerializedName("env")
    @Expose
    private List<V1EnvVar> env = new ArrayList<V1EnvVar>();
    /**
     * Resource requirements for the Cassandra container.
     * 
     */
    @SerializedName("resources")
    @Expose
    private V1ResourceRequirements resources;
    @SerializedName("dataVolumeClaim")
    @Expose
    private V1PersistentVolumeClaimSpec dataVolumeClaim;
    /**
     * Name of the CassandraBackup to restore from
     * 
     */
    @SerializedName("restoreFromBackup")
    @Expose
    private java.lang.String restoreFromBackup;
    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     * 
     */
    @SerializedName("userConfigMapVolumeSource")
    @Expose
    private V1ConfigMapVolumeSource userConfigMapVolumeSource;
    /**
     * Name of an optional secret that contains cassandra related secrets
     * 
     */
    @SerializedName("userSecretVolumeSource")
    @Expose
    private V1SecretVolumeSource userSecretVolumeSource;
    /**
     * Enable Prometheus support.
     * 
     */
    @SerializedName("prometheusSupport")
    @Expose
    private Boolean prometheusSupport;
    /**
     * Labels to attach to the Prometheus ServiceMonitor for this data center.
     * 
     */
    @SerializedName("prometheusServiceMonitorLabels")
    @Expose
    private Map<String, String> prometheusServiceMonitorLabels;
    /**
     * Attempt to run privileged configuration options for better performance
     * 
     */
    @SerializedName("privilegedSupported")
    @Expose
    private Boolean privilegedSupported;
    /**
     * Enable elasticsearch service
     * 
     */
    @SerializedName("elasticsearchEnabled")
    @Expose
    private Boolean elasticsearchEnabled = true;
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;
    @SerializedName("authentication")
    @Expose
    private Boolean authentication = false;
    @SerializedName("authorization")
    @Expose
    private Boolean authorization = false;
    @SerializedName("enterprise")
    @Expose
    private com.instaclustr.model.k8s.cassandra.Enterprise enterprise;

    /**
     * Number of Cassandra nodes in this data center.
     * 
     */
    public int getReplicas() {
        return replicas;
    }

    /**
     * Number of Cassandra nodes in this data center.
     * 
     */
    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public DataCenterSpec withReplicas(int replicas) {
        this.replicas = replicas;
        return this;
    }

    public java.lang.String getElassandraImage() {
        return elassandraImage;
    }

    public void setElassandraImage(java.lang.String elassandraImage) {
        this.elassandraImage = elassandraImage;
    }

    public DataCenterSpec withElassandraImage(java.lang.String elassandraImage) {
        this.elassandraImage = elassandraImage;
        return this;
    }

    public java.lang.String getEnterpriseImage() {
        return enterpriseImage;
    }

    public void setEnterpriseImage(java.lang.String enterpriseImage) {
        this.enterpriseImage = enterpriseImage;
    }

    public DataCenterSpec withEnterpriseImage(java.lang.String enterpriseImage) {
        this.enterpriseImage = enterpriseImage;
        return this;
    }

    public java.lang.String getSidecarImage() {
        return sidecarImage;
    }

    public void setSidecarImage(java.lang.String sidecarImage) {
        this.sidecarImage = sidecarImage;
    }

    public DataCenterSpec withSidecarImage(java.lang.String sidecarImage) {
        this.sidecarImage = sidecarImage;
        return this;
    }

    public java.lang.String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(java.lang.String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public DataCenterSpec withImagePullPolicy(java.lang.String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
        return this;
    }

    public java.lang.String getImagePullSecret() {
        return imagePullSecret;
    }

    public void setImagePullSecret(java.lang.String imagePullSecret) {
        this.imagePullSecret = imagePullSecret;
    }

    public DataCenterSpec withImagePullSecret(java.lang.String imagePullSecret) {
        this.imagePullSecret = imagePullSecret;
        return this;
    }

    /**
     * List of environment variables to inject in the Cassandra & Sidecar container.
     * 
     */
    public List<V1EnvVar> getEnv() {
        return env;
    }

    /**
     * List of environment variables to inject in the Cassandra & Sidecar container.
     * 
     */
    public void setEnv(List<V1EnvVar> env) {
        this.env = env;
    }

    public DataCenterSpec withEnv(List<V1EnvVar> env) {
        this.env = env;
        return this;
    }

    /**
     * Resource requirements for the Cassandra container.
     * 
     */
    public V1ResourceRequirements getResources() {
        return resources;
    }

    /**
     * Resource requirements for the Cassandra container.
     * 
     */
    public void setResources(V1ResourceRequirements resources) {
        this.resources = resources;
    }

    public DataCenterSpec withResources(V1ResourceRequirements resources) {
        this.resources = resources;
        return this;
    }

    public V1PersistentVolumeClaimSpec getDataVolumeClaim() {
        return dataVolumeClaim;
    }

    public void setDataVolumeClaim(V1PersistentVolumeClaimSpec dataVolumeClaim) {
        this.dataVolumeClaim = dataVolumeClaim;
    }

    public DataCenterSpec withDataVolumeClaim(V1PersistentVolumeClaimSpec dataVolumeClaim) {
        this.dataVolumeClaim = dataVolumeClaim;
        return this;
    }

    /**
     * Name of the CassandraBackup to restore from
     * 
     */
    public java.lang.String getRestoreFromBackup() {
        return restoreFromBackup;
    }

    /**
     * Name of the CassandraBackup to restore from
     * 
     */
    public void setRestoreFromBackup(java.lang.String restoreFromBackup) {
        this.restoreFromBackup = restoreFromBackup;
    }

    public DataCenterSpec withRestoreFromBackup(java.lang.String restoreFromBackup) {
        this.restoreFromBackup = restoreFromBackup;
        return this;
    }

    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     * 
     */
    public V1ConfigMapVolumeSource getUserConfigMapVolumeSource() {
        return userConfigMapVolumeSource;
    }

    /**
     * Name of an optional config map that contains cassandra configuration in the form of yaml fragments
     * 
     */
    public void setUserConfigMapVolumeSource(V1ConfigMapVolumeSource userConfigMapVolumeSource) {
        this.userConfigMapVolumeSource = userConfigMapVolumeSource;
    }

    public DataCenterSpec withUserConfigMapVolumeSource(V1ConfigMapVolumeSource userConfigMapVolumeSource) {
        this.userConfigMapVolumeSource = userConfigMapVolumeSource;
        return this;
    }

    /**
     * Name of an optional secret that contains cassandra related secrets
     * 
     */
    public V1SecretVolumeSource getUserSecretVolumeSource() {
        return userSecretVolumeSource;
    }

    /**
     * Name of an optional secret that contains cassandra related secrets
     * 
     */
    public void setUserSecretVolumeSource(V1SecretVolumeSource userSecretVolumeSource) {
        this.userSecretVolumeSource = userSecretVolumeSource;
    }

    public DataCenterSpec withUserSecretVolumeSource(V1SecretVolumeSource userSecretVolumeSource) {
        this.userSecretVolumeSource = userSecretVolumeSource;
        return this;
    }

    /**
     * Enable Prometheus support.
     * 
     */
    public Boolean getPrometheusSupport() {
        return prometheusSupport;
    }

    /**
     * Enable Prometheus support.
     * 
     */
    public void setPrometheusSupport(Boolean prometheusSupport) {
        this.prometheusSupport = prometheusSupport;
    }

    public DataCenterSpec withPrometheusSupport(Boolean prometheusSupport) {
        this.prometheusSupport = prometheusSupport;
        return this;
    }

    /**
     * Labels to attach to the Prometheus ServiceMonitor for this data center.
     * 
     */
    public Map<String, String> getPrometheusServiceMonitorLabels() {
        return prometheusServiceMonitorLabels;
    }

    /**
     * Labels to attach to the Prometheus ServiceMonitor for this data center.
     * 
     */
    public void setPrometheusServiceMonitorLabels(Map<String, String> prometheusServiceMonitorLabels) {
        this.prometheusServiceMonitorLabels = prometheusServiceMonitorLabels;
    }

    public DataCenterSpec withPrometheusServiceMonitorLabels(Map<String, String> prometheusServiceMonitorLabels) {
        this.prometheusServiceMonitorLabels = prometheusServiceMonitorLabels;
        return this;
    }

    /**
     * Attempt to run privileged configuration options for better performance
     * 
     */
    public Boolean getPrivilegedSupported() {
        return privilegedSupported;
    }

    /**
     * Attempt to run privileged configuration options for better performance
     * 
     */
    public void setPrivilegedSupported(Boolean privilegedSupported) {
        this.privilegedSupported = privilegedSupported;
    }

    public DataCenterSpec withPrivilegedSupported(Boolean privilegedSupported) {
        this.privilegedSupported = privilegedSupported;
        return this;
    }

    /**
     * Enable elasticsearch service
     * 
     */
    public Boolean getElasticsearchEnabled() {
        return elasticsearchEnabled;
    }

    /**
     * Enable elasticsearch service
     * 
     */
    public void setElasticsearchEnabled(Boolean elasticsearchEnabled) {
        this.elasticsearchEnabled = elasticsearchEnabled;
    }

    public DataCenterSpec withElasticsearchEnabled(Boolean elasticsearchEnabled) {
        this.elasticsearchEnabled = elasticsearchEnabled;
        return this;
    }

    public Boolean getSsl() {
        return ssl;
    }

    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
    }

    public DataCenterSpec withSsl(Boolean ssl) {
        this.ssl = ssl;
        return this;
    }

    public Boolean getAuthentication() {
        return authentication;
    }

    public void setAuthentication(Boolean authentication) {
        this.authentication = authentication;
    }

    public DataCenterSpec withAuthentication(Boolean authentication) {
        this.authentication = authentication;
        return this;
    }

    public Boolean getAuthorization() {
        return authorization;
    }

    public void setAuthorization(Boolean authorization) {
        this.authorization = authorization;
    }

    public DataCenterSpec withAuthorization(Boolean authorization) {
        this.authorization = authorization;
        return this;
    }

    public com.instaclustr.model.k8s.cassandra.Enterprise getEnterprise() {
        return enterprise;
    }

    public void setEnterprise(com.instaclustr.model.k8s.cassandra.Enterprise enterprise) {
        this.enterprise = enterprise;
    }

    public DataCenterSpec withEnterprise(com.instaclustr.model.k8s.cassandra.Enterprise enterprise) {
        this.enterprise = enterprise;
        return this;
    }

    @Override
    public java.lang.String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(DataCenterSpec.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("replicas");
        sb.append('=');
        sb.append(this.replicas);
        sb.append(',');
        sb.append("elassandraImage");
        sb.append('=');
        sb.append(((this.elassandraImage == null)?"<null>":this.elassandraImage));
        sb.append(',');
        sb.append("enterpriseImage");
        sb.append('=');
        sb.append(((this.enterpriseImage == null)?"<null>":this.enterpriseImage));
        sb.append(',');
        sb.append("sidecarImage");
        sb.append('=');
        sb.append(((this.sidecarImage == null)?"<null>":this.sidecarImage));
        sb.append(',');
        sb.append("imagePullPolicy");
        sb.append('=');
        sb.append(((this.imagePullPolicy == null)?"<null>":this.imagePullPolicy));
        sb.append(',');
        sb.append("imagePullSecret");
        sb.append('=');
        sb.append(((this.imagePullSecret == null)?"<null>":this.imagePullSecret));
        sb.append(',');
        sb.append("env");
        sb.append('=');
        sb.append(((this.env == null)?"<null>":this.env));
        sb.append(',');
        sb.append("resources");
        sb.append('=');
        sb.append(((this.resources == null)?"<null>":this.resources));
        sb.append(',');
        sb.append("dataVolumeClaim");
        sb.append('=');
        sb.append(((this.dataVolumeClaim == null)?"<null>":this.dataVolumeClaim));
        sb.append(',');
        sb.append("restoreFromBackup");
        sb.append('=');
        sb.append(((this.restoreFromBackup == null)?"<null>":this.restoreFromBackup));
        sb.append(',');
        sb.append("userConfigMapVolumeSource");
        sb.append('=');
        sb.append(((this.userConfigMapVolumeSource == null)?"<null>":this.userConfigMapVolumeSource));
        sb.append(',');
        sb.append("userSecretVolumeSource");
        sb.append('=');
        sb.append(((this.userSecretVolumeSource == null)?"<null>":this.userSecretVolumeSource));
        sb.append(',');
        sb.append("prometheusSupport");
        sb.append('=');
        sb.append(((this.prometheusSupport == null)?"<null>":this.prometheusSupport));
        sb.append(',');
        sb.append("prometheusServiceMonitorLabels");
        sb.append('=');
        sb.append(((this.prometheusServiceMonitorLabels == null)?"<null>":this.prometheusServiceMonitorLabels));
        sb.append(',');
        sb.append("privilegedSupported");
        sb.append('=');
        sb.append(((this.privilegedSupported == null)?"<null>":this.privilegedSupported));
        sb.append(',');
        sb.append("elasticsearchEnabled");
        sb.append('=');
        sb.append(((this.elasticsearchEnabled == null)?"<null>":this.elasticsearchEnabled));
        sb.append(',');
        sb.append("ssl");
        sb.append('=');
        sb.append(((this.ssl == null)?"<null>":this.ssl));
        sb.append(',');
        sb.append("authentication");
        sb.append('=');
        sb.append(((this.authentication == null)?"<null>":this.authentication));
        sb.append(',');
        sb.append("authorization");
        sb.append('=');
        sb.append(((this.authorization == null)?"<null>":this.authorization));
        sb.append(',');
        sb.append("enterprise");
        sb.append('=');
        sb.append(((this.enterprise == null)?"<null>":this.enterprise));
        sb.append(',');
        if (sb.charAt((sb.length()- 1)) == ',') {
            sb.setCharAt((sb.length()- 1), ']');
        } else {
            sb.append(']');
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = ((result* 31)+((this.imagePullPolicy == null)? 0 :this.imagePullPolicy.hashCode()));
        result = ((result* 31)+((this.enterpriseImage == null)? 0 :this.enterpriseImage.hashCode()));
        result = ((result* 31)+((this.sidecarImage == null)? 0 :this.sidecarImage.hashCode()));
        result = ((result* 31)+ this.replicas);
        result = ((result* 31)+((this.prometheusSupport == null)? 0 :this.prometheusSupport.hashCode()));
        result = ((result* 31)+((this.enterprise == null)? 0 :this.enterprise.hashCode()));
        result = ((result* 31)+((this.privilegedSupported == null)? 0 :this.privilegedSupported.hashCode()));
        result = ((result* 31)+((this.elassandraImage == null)? 0 :this.elassandraImage.hashCode()));
        result = ((result* 31)+((this.resources == null)? 0 :this.resources.hashCode()));
        result = ((result* 31)+((this.prometheusServiceMonitorLabels == null)? 0 :this.prometheusServiceMonitorLabels.hashCode()));
        result = ((result* 31)+((this.env == null)? 0 :this.env.hashCode()));
        result = ((result* 31)+((this.ssl == null)? 0 :this.ssl.hashCode()));
        result = ((result* 31)+((this.userConfigMapVolumeSource == null)? 0 :this.userConfigMapVolumeSource.hashCode()));
        result = ((result* 31)+((this.userSecretVolumeSource == null)? 0 :this.userSecretVolumeSource.hashCode()));
        result = ((result* 31)+((this.authorization == null)? 0 :this.authorization.hashCode()));
        result = ((result* 31)+((this.imagePullSecret == null)? 0 :this.imagePullSecret.hashCode()));
        result = ((result* 31)+((this.elasticsearchEnabled == null)? 0 :this.elasticsearchEnabled.hashCode()));
        result = ((result* 31)+((this.dataVolumeClaim == null)? 0 :this.dataVolumeClaim.hashCode()));
        result = ((result* 31)+((this.restoreFromBackup == null)? 0 :this.restoreFromBackup.hashCode()));
        result = ((result* 31)+((this.authentication == null)? 0 :this.authentication.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof DataCenterSpec) == false) {
            return false;
        }
        DataCenterSpec rhs = ((DataCenterSpec) other);
        return (((((((((((((((((((((this.imagePullPolicy == rhs.imagePullPolicy)||((this.imagePullPolicy!= null)&&this.imagePullPolicy.equals(rhs.imagePullPolicy)))&&((this.enterpriseImage == rhs.enterpriseImage)||((this.enterpriseImage!= null)&&this.enterpriseImage.equals(rhs.enterpriseImage))))&&((this.sidecarImage == rhs.sidecarImage)||((this.sidecarImage!= null)&&this.sidecarImage.equals(rhs.sidecarImage))))&&(this.replicas == rhs.replicas))&&((this.prometheusSupport == rhs.prometheusSupport)||((this.prometheusSupport!= null)&&this.prometheusSupport.equals(rhs.prometheusSupport))))&&((this.enterprise == rhs.enterprise)||((this.enterprise!= null)&&this.enterprise.equals(rhs.enterprise))))&&((this.privilegedSupported == rhs.privilegedSupported)||((this.privilegedSupported!= null)&&this.privilegedSupported.equals(rhs.privilegedSupported))))&&((this.elassandraImage == rhs.elassandraImage)||((this.elassandraImage!= null)&&this.elassandraImage.equals(rhs.elassandraImage))))&&((this.resources == rhs.resources)||((this.resources!= null)&&this.resources.equals(rhs.resources))))&&((this.prometheusServiceMonitorLabels == rhs.prometheusServiceMonitorLabels)||((this.prometheusServiceMonitorLabels!= null)&&this.prometheusServiceMonitorLabels.equals(rhs.prometheusServiceMonitorLabels))))&&((this.env == rhs.env)||((this.env!= null)&&this.env.equals(rhs.env))))&&((this.ssl == rhs.ssl)||((this.ssl!= null)&&this.ssl.equals(rhs.ssl))))&&((this.userConfigMapVolumeSource == rhs.userConfigMapVolumeSource)||((this.userConfigMapVolumeSource!= null)&&this.userConfigMapVolumeSource.equals(rhs.userConfigMapVolumeSource))))&&((this.userSecretVolumeSource == rhs.userSecretVolumeSource)||((this.userSecretVolumeSource!= null)&&this.userSecretVolumeSource.equals(rhs.userSecretVolumeSource))))&&((this.authorization == rhs.authorization)||((this.authorization!= null)&&this.authorization.equals(rhs.authorization))))&&((this.imagePullSecret == rhs.imagePullSecret)||((this.imagePullSecret!= null)&&this.imagePullSecret.equals(rhs.imagePullSecret))))&&((this.elasticsearchEnabled == rhs.elasticsearchEnabled)||((this.elasticsearchEnabled!= null)&&this.elasticsearchEnabled.equals(rhs.elasticsearchEnabled))))&&((this.dataVolumeClaim == rhs.dataVolumeClaim)||((this.dataVolumeClaim!= null)&&this.dataVolumeClaim.equals(rhs.dataVolumeClaim))))&&((this.restoreFromBackup == rhs.restoreFromBackup)||((this.restoreFromBackup!= null)&&this.restoreFromBackup.equals(rhs.restoreFromBackup))))&&((this.authentication == rhs.authentication)||((this.authentication!= null)&&this.authentication.equals(rhs.authentication))));
    }

}
