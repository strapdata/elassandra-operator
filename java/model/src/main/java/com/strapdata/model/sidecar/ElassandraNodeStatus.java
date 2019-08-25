package com.strapdata.model.sidecar;

/**
 * This has been renamed from NodeStatus ElassandraNodeStatus to avoid confusion with k8s nodes
 */
public enum ElassandraNodeStatus {
    UNKNOWN, STARTING, NORMAL, JOINING, LEAVING, DECOMMISSIONED, MOVING, DRAINING, DRAINED;
}