package com.strapdata.model.k8s.cassandra;

public enum RackPhase {
    CREATING, STARTING, RUNNING, UPDATING, SCALING_UP, SCALING_DOWN, FAILED, SCHEDULING_PENDING, PARKING, PARKED
}
