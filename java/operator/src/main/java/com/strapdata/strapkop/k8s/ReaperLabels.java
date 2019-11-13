package com.strapdata.strapkop.k8s;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public interface ReaperLabels {
    Map<String, String> PODS_SELECTOR = ImmutableMap.of(
            "app.kubernetes.io/managed-by", "elassandra-operator",
            "app", "reaper"
    );
}
