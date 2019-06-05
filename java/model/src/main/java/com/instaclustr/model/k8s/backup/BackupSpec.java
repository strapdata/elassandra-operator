
package com.instaclustr.model.k8s.backup;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1LabelSelector;

public class BackupSpec {

    @SerializedName("selector")
    @Expose
    private V1LabelSelector selector;
    @SerializedName("backupType")
    @Expose
    private String backupType;
    @SerializedName("target")
    @Expose
    private String target;

    public V1LabelSelector getSelector() {
        return selector;
    }

    public void setSelector(V1LabelSelector selector) {
        this.selector = selector;
    }

    public BackupSpec withSelector(V1LabelSelector selector) {
        this.selector = selector;
        return this;
    }

    public String getBackupType() {
        return backupType;
    }

    public void setBackupType(String backupType) {
        this.backupType = backupType;
    }

    public BackupSpec withBackupType(String backupType) {
        this.backupType = backupType;
        return this;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public BackupSpec withTarget(String target) {
        this.target = target;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(BackupSpec.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("selector");
        sb.append('=');
        sb.append(((this.selector == null)?"<null>":this.selector));
        sb.append(',');
        sb.append("backupType");
        sb.append('=');
        sb.append(((this.backupType == null)?"<null>":this.backupType));
        sb.append(',');
        sb.append("target");
        sb.append('=');
        sb.append(((this.target == null)?"<null>":this.target));
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
        result = ((result* 31)+((this.selector == null)? 0 :this.selector.hashCode()));
        result = ((result* 31)+((this.backupType == null)? 0 :this.backupType.hashCode()));
        result = ((result* 31)+((this.target == null)? 0 :this.target.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof BackupSpec) == false) {
            return false;
        }
        BackupSpec rhs = ((BackupSpec) other);
        return ((((this.selector == rhs.selector)||((this.selector!= null)&&this.selector.equals(rhs.selector)))&&((this.backupType == rhs.backupType)||((this.backupType!= null)&&this.backupType.equals(rhs.backupType))))&&((this.target == rhs.target)||((this.target!= null)&&this.target.equals(rhs.target))));
    }

}
