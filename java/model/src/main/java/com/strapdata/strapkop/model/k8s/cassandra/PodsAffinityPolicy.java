package com.strapdata.strapkop.model.k8s.cassandra;

/**
 * Elassandra pods affinity policy.
 */
public enum PodsAffinityPolicy {
    STRICT, /* schedule elassandra pods only on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
    SLACK   /* schedule elassandra pods preferably on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
}
