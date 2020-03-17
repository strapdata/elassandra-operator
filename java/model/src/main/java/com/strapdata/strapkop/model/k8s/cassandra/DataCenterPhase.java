package com.strapdata.strapkop.model.k8s.cassandra;

/**
 * The desired datacenter state
 */
public enum DataCenterPhase {
    RUNNING, PARKED;

    public boolean isRunning() {
        return this.equals(RUNNING);
    }
}
