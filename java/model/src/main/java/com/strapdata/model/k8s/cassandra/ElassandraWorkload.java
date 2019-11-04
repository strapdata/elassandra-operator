package com.strapdata.model.k8s.cassandra;

/**
 * Elassandra workload type.
 */
public enum ElassandraWorkload {
    WRITE, /* Elassandra will mainly receive writes */
    READ_WRITE, /* Elassandra will receive an equivalent number of reads & writes */
    READ   /* Elassandra will mainly receive reads */
}
