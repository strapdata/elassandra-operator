
package com.instaclustr.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Aaa {

    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;
    @SerializedName("audit")
    @Expose
    private Boolean audit = false;
    @SerializedName("sharedSecret")
    @Expose
    private String sharedSecret;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Aaa withEnabled(Boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Boolean getAudit() {
        return audit;
    }

    public void setAudit(Boolean audit) {
        this.audit = audit;
    }

    public Aaa withAudit(Boolean audit) {
        this.audit = audit;
        return this;
    }

    public String getSharedSecret() {
        return sharedSecret;
    }

    public void setSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
    }

    public Aaa withSharedSecret(String sharedSecret) {
        this.sharedSecret = sharedSecret;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Aaa.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("enabled");
        sb.append('=');
        sb.append(((this.enabled == null)?"<null>":this.enabled));
        sb.append(',');
        sb.append("audit");
        sb.append('=');
        sb.append(((this.audit == null)?"<null>":this.audit));
        sb.append(',');
        sb.append("sharedSecret");
        sb.append('=');
        sb.append(((this.sharedSecret == null)?"<null>":this.sharedSecret));
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
        result = ((result* 31)+((this.sharedSecret == null)? 0 :this.sharedSecret.hashCode()));
        result = ((result* 31)+((this.enabled == null)? 0 :this.enabled.hashCode()));
        result = ((result* 31)+((this.audit == null)? 0 :this.audit.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof Aaa) == false) {
            return false;
        }
        Aaa rhs = ((Aaa) other);
        return ((((this.sharedSecret == rhs.sharedSecret)||((this.sharedSecret!= null)&&this.sharedSecret.equals(rhs.sharedSecret)))&&((this.enabled == rhs.enabled)||((this.enabled!= null)&&this.enabled.equals(rhs.enabled))))&&((this.audit == rhs.audit)||((this.audit!= null)&&this.audit.equals(rhs.audit))));
    }

}
