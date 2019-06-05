
package com.instaclustr.model.k8s.backup;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class BackupStatus {

    @SerializedName("progress")
    @Expose
    private String progress;

    public String getProgress() {
        return progress;
    }

    public void setProgress(String progress) {
        this.progress = progress;
    }

    public BackupStatus withProgress(String progress) {
        this.progress = progress;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(BackupStatus.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("progress");
        sb.append('=');
        sb.append(((this.progress == null)?"<null>":this.progress));
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
        result = ((result* 31)+((this.progress == null)? 0 :this.progress.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof BackupStatus) == false) {
            return false;
        }
        BackupStatus rhs = ((BackupStatus) other);
        return ((this.progress == rhs.progress)||((this.progress!= null)&&this.progress.equals(rhs.progress)));
    }

}
