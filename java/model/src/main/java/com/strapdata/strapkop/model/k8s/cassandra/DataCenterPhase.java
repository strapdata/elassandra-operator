package com.strapdata.strapkop.model.k8s.cassandra;

public enum DataCenterPhase {
    CREATING, SCALING_DOWN, SCALING_UP, RUNNING, UPDATING, ERROR, ROLLING_BACK, PARKING, PARKED, STARTING;

    public boolean isReady() {
        return this.equals(RUNNING) ||
                this.equals(UPDATING) ||
                this.equals(ERROR) ||
                this.equals(SCALING_DOWN) ||
                this.equals(SCALING_UP) ||
                this.equals(ROLLING_BACK);
    }
}
