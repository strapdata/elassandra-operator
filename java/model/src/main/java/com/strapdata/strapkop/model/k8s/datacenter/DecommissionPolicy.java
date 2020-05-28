package com.strapdata.strapkop.model.k8s.datacenter;

/**
 * PVC decommissioning policy
 */
public enum DecommissionPolicy {
    KEEP_PVC,
    DELETE_PVC,
    BACKUP_AND_DELETE_PVC
}
