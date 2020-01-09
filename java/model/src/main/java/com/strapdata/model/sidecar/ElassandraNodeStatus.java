package com.strapdata.model.sidecar;

/**
 * This has been renamed from NodeStatus ElassandraNodeStatus to avoid confusion with k8s nodes
 */
public enum ElassandraNodeStatus {
    UNKNOWN,
    STARTING,
    NORMAL,
    JOINING,
    LEAVING,
    DECOMMISSIONED,
    MOVING,
    DRAINING,
    DRAINED,
    DOWN,   // temporary down due to a maintenance operation
    FAILED; // failed to start or restart

    public boolean isMoving() {
        return JOINING.equals(this) || DRAINING.equals(this) || LEAVING.equals(this) || MOVING.equals(this) || STARTING.equals(this);
    }

    // test if the Node belongs to the cluster
    public boolean isJoined() {
        return MOVING.equals(this) || NORMAL.equals(this);
    }
}