package com.strapdata.strapkop.model.k8s;

import java.io.InputStream;

public class StrapdataCrdGroup {
    public static final String GROUP = "elassandra.strapdata.com";

    public static InputStream getDataCenterCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/datacenter-crd.yaml");
    }

    public static InputStream getTaskCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/task-crd.yaml");
    }
}
