package com.strapdata.strapkop.model.k8s.cassandra;

/**
 * Cassandra reaper deployment status
 */
public enum ReaperPhase {
    NONE,
    KEYSPACE_CREATED,   /* reaper keyspace created */
    ROLE_CREATED,       /* reaper role created */
    DEPLOYED,           /* reaper is k8s deployed */
    RUNNING,            /* reaper is available */
    REGISTERED;         /* datacenter is registred through the reaper API */
}
