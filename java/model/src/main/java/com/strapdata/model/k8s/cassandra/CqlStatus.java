package com.strapdata.model.k8s.cassandra;

/**
 * CQL connection status
 */
public enum CqlStatus {
    NOT_STARTED,
    ESTABLISHED,
    ERRORED
}