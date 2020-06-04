package com.strapdata.strapkop.model.k8s.datacenter;

/**
 * Elassandra workload type.
 */
public enum AutoScaleMode {
    MANUAL,           // datacenter scale manually by updating the repliacs number
    NODEPOOL          // datacenter auomatically scale with one Elassandra node per Kubernetes node
}
