package com.strapdata.model.k8s.cassandra;

public enum NodeAffinityPolicy {
    STRICT, /* schedule pods only on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
    SLACK   /* schedule pods preferably on nodes in the matching the failure-domain.beta.kubernetes.io/zone label */
}
