
package com.instaclustr.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class Enterprise {

    @SerializedName("enabled")
    @Expose
    private Boolean enabled = false;
    @SerializedName("jmx")
    @Expose
    private Boolean jmx = false;
    @SerializedName("https")
    @Expose
    private Boolean https = false;
    @SerializedName("ssl")
    @Expose
    private Boolean ssl = false;
    @SerializedName("aaa")
    @Expose
    private Aaa aaa;
    @SerializedName("cbs")
    @Expose
    private Boolean cbs = false;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Enterprise withEnabled(Boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Boolean getJmx() {
        return jmx;
    }

    public void setJmx(Boolean jmx) {
        this.jmx = jmx;
    }

    public Enterprise withJmx(Boolean jmx) {
        this.jmx = jmx;
        return this;
    }

    public Boolean getHttps() {
        return https;
    }

    public void setHttps(Boolean https) {
        this.https = https;
    }

    public Enterprise withHttps(Boolean https) {
        this.https = https;
        return this;
    }

    public Boolean getSsl() {
        return ssl;
    }

    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
    }

    public Enterprise withSsl(Boolean ssl) {
        this.ssl = ssl;
        return this;
    }

    public Aaa getAaa() {
        return aaa;
    }

    public void setAaa(Aaa aaa) {
        this.aaa = aaa;
    }

    public Enterprise withAaa(Aaa aaa) {
        this.aaa = aaa;
        return this;
    }

    public Boolean getCbs() {
        return cbs;
    }

    public void setCbs(Boolean cbs) {
        this.cbs = cbs;
    }

    public Enterprise withCbs(Boolean cbs) {
        this.cbs = cbs;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Enterprise.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("enabled");
        sb.append('=');
        sb.append(((this.enabled == null)?"<null>":this.enabled));
        sb.append(',');
        sb.append("jmx");
        sb.append('=');
        sb.append(((this.jmx == null)?"<null>":this.jmx));
        sb.append(',');
        sb.append("https");
        sb.append('=');
        sb.append(((this.https == null)?"<null>":this.https));
        sb.append(',');
        sb.append("ssl");
        sb.append('=');
        sb.append(((this.ssl == null)?"<null>":this.ssl));
        sb.append(',');
        sb.append("aaa");
        sb.append('=');
        sb.append(((this.aaa == null)?"<null>":this.aaa));
        sb.append(',');
        sb.append("cbs");
        sb.append('=');
        sb.append(((this.cbs == null)?"<null>":this.cbs));
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
        result = ((result* 31)+((this.aaa == null)? 0 :this.aaa.hashCode()));
        result = ((result* 31)+((this.jmx == null)? 0 :this.jmx.hashCode()));
        result = ((result* 31)+((this.cbs == null)? 0 :this.cbs.hashCode()));
        result = ((result* 31)+((this.https == null)? 0 :this.https.hashCode()));
        result = ((result* 31)+((this.ssl == null)? 0 :this.ssl.hashCode()));
        result = ((result* 31)+((this.enabled == null)? 0 :this.enabled.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof Enterprise) == false) {
            return false;
        }
        Enterprise rhs = ((Enterprise) other);
        return (((((((this.aaa == rhs.aaa)||((this.aaa!= null)&&this.aaa.equals(rhs.aaa)))&&((this.jmx == rhs.jmx)||((this.jmx!= null)&&this.jmx.equals(rhs.jmx))))&&((this.cbs == rhs.cbs)||((this.cbs!= null)&&this.cbs.equals(rhs.cbs))))&&((this.https == rhs.https)||((this.https!= null)&&this.https.equals(rhs.https))))&&((this.ssl == rhs.ssl)||((this.ssl!= null)&&this.ssl.equals(rhs.ssl))))&&((this.enabled == rhs.enabled)||((this.enabled!= null)&&this.enabled.equals(rhs.enabled))));
    }

}
