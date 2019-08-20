package com.strapdata.model.k8s.cassandra;

public enum ReaperStatus {
    NONE, KEYSPACE_INITIALIZED, REGISTERED;
    
    public boolean isInitialized() {
        return this.equals(KEYSPACE_INITIALIZED) || this.equals(REGISTERED);
    }
}
