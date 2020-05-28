package com.strapdata.strapkop.model.k8s.datacenter;

/**
 * Cassandra reaper deployment status
 */
public enum ReaperPhase {
    NONE,
    DEPLOYED,           /* reaper is k8s deployed */
    RUNNING,            /* reaper is available */
    REGISTERED;         /* datacenter is registred through the reaper API */
}
