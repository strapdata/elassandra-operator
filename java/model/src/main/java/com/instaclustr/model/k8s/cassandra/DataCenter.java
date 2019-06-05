
package com.instaclustr.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ObjectMeta;


/**
 * 
 * 
 */
public class DataCenter {

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion;
    @SerializedName("kind")
    @Expose
    private String kind;
    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;
    @SerializedName("spec")
    @Expose
    private DataCenterSpec spec;

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public DataCenter withApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
        return this;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public DataCenter withKind(String kind) {
        this.kind = kind;
        return this;
    }

    public V1ObjectMeta getMetadata() {
        return metadata;
    }

    public void setMetadata(V1ObjectMeta metadata) {
        this.metadata = metadata;
    }

    public DataCenter withMetadata(V1ObjectMeta metadata) {
        this.metadata = metadata;
        return this;
    }

    public com.instaclustr.model.k8s.cassandra.DataCenterSpec getSpec() {
        return spec;
    }

    public void setSpec(com.instaclustr.model.k8s.cassandra.DataCenterSpec spec) {
        this.spec = spec;
    }

    public DataCenter withSpec(com.instaclustr.model.k8s.cassandra.DataCenterSpec spec) {
        this.spec = spec;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(DataCenter.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("apiVersion");
        sb.append('=');
        sb.append(((this.apiVersion == null)?"<null>":this.apiVersion));
        sb.append(',');
        sb.append("kind");
        sb.append('=');
        sb.append(((this.kind == null)?"<null>":this.kind));
        sb.append(',');
        sb.append("metadata");
        sb.append('=');
        sb.append(((this.metadata == null)?"<null>":this.metadata));
        sb.append(',');
        sb.append("spec");
        sb.append('=');
        sb.append(((this.spec == null)?"<null>":this.spec));
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
        result = ((result* 31)+((this.metadata == null)? 0 :this.metadata.hashCode()));
        result = ((result* 31)+((this.apiVersion == null)? 0 :this.apiVersion.hashCode()));
        result = ((result* 31)+((this.kind == null)? 0 :this.kind.hashCode()));
        result = ((result* 31)+((this.spec == null)? 0 :this.spec.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof DataCenter) == false) {
            return false;
        }
        DataCenter rhs = ((DataCenter) other);
        return (((((this.metadata == rhs.metadata)||((this.metadata!= null)&&this.metadata.equals(rhs.metadata)))&&((this.apiVersion == rhs.apiVersion)||((this.apiVersion!= null)&&this.apiVersion.equals(rhs.apiVersion))))&&((this.kind == rhs.kind)||((this.kind!= null)&&this.kind.equals(rhs.kind))))&&((this.spec == rhs.spec)||((this.spec!= null)&&this.spec.equals(rhs.spec))));
    }

}
