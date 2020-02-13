package com.strapdata.strapkop.model.k8s.cassandra;

public enum BlockReason {
    NONE, ADMIN, BACKUP, REPAIR, CLEANUP, REBUILD, REBUILD_INDEX, DECOMMISSION
}
