package com.strapdata.model.sidecar;

/**
 * This has been renamed from NodeStatus ElassandraPodCrdStatus to avoid confusion with k8s nodes
 */
public enum ElassandraPodStatus {
    STARTING, NORMAL, JOINING, LEAVING, DECOMMISSIONED, MOVING, DRAINING, DRAINED, UNKNOWN;
}