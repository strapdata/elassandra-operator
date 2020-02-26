package com.strapdata.strapkop.sidecar;

public interface ElasticNodeMetricsMBean {
    public String[] getDataPaths();
    public String getStatus();

    public boolean isSearchEnabled();
    public void setSearchEnabled(boolean searchEnabled);

    public boolean isAutoEnableSearch();
    public void setAutoEnableSearch(boolean newValue);
}
