package com.strapdata.model.k8s.cassandra;

public enum DecommissionPolicy {
    KEEP_PVC, DELETE_PVC, BACKUP_AND_DELETE_PVC
}
