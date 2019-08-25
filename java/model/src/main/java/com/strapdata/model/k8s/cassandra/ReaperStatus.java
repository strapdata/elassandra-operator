package com.strapdata.model.k8s.cassandra;

/**
 * Cassandra reaper deployment status
 */
public enum ReaperStatus {
    NONE,
    KEYSPACE_INITIALIZED,   /* reaper role and keyspace are created */
    REGISTERED;             /* datacenter is registred through the reaper API */
    
    public boolean isInitialized() {
        return this.equals(KEYSPACE_INITIALIZED) || this.equals(REGISTERED);
    }

    public boolean isRegistred() {
        return this.equals(REGISTERED);
    }
}
