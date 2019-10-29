package com.strapdata.model.k8s.cassandra;

/**
 * Cassandra reaper deployment status
 */
public enum ReaperPhase {
    NONE,
    KEYSPACE_CREATED,   /* reaper keyspace created */
    ROLE_CREATED,       /* reaper role created */
    REGISTERED;         /* datacenter is registred through the reaper API */
}
