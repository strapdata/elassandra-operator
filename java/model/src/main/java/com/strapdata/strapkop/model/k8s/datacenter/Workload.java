package com.strapdata.strapkop.model.k8s.datacenter;

/**
 * Elassandra workload type.
 */
public enum Workload {
    WRITE, /* Elassandra will mainly receive writes */
    READ_WRITE, /* Elassandra will receive an equivalent number of reads & writes */
    READ   /* Elassandra will mainly receive reads */
}
