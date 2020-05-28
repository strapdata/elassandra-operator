package com.strapdata.strapkop.model.k8s.datacenter;

/**
 * Elassandra workload type.
 */
public enum AutoScaleMode {
    MANUAL,                 // datacenter scale manually by updating the repliacs number
    NODEPOOL_SIZE,          // datacenter auomatically scale with one Elassandra node per Kubernetes node
    NODEPOOL_SIZE_MINUS_ONE,// datacenter auomatically scale with one Elassandra node per Kubernetes node minus one
}
