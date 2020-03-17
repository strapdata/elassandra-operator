package com.strapdata.strapkop.model.k8s.cassandra;

/**
 * The DC or rack health status
 * Yellow = 1 pod/rack or one rack/dc unavailable
 * Red = 1+ pod/rack or more than one rack not green in the datacenter
 */
public enum Health {
    UNKNOWN, GREEN, YELLOW, RED;
}
