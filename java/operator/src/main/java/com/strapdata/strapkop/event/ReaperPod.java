package com.strapdata.strapkop.event;

import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.models.V1ContainerStatus;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodStatus;

import java.util.Map;

public class ReaperPod {
    private final V1Pod pod;

    public ReaperPod(V1Pod pod) {
        this.pod = pod;
    }

    public String getClusterName() {
        return extractLabel("cluster");
    }

    public String getDatacenter() {
        return extractLabel("datacenter");
    }

    public String getElassandraDatacenter() {
        return extractLabel(OperatorLabels.PARENT);
    }

    private String extractLabel(String label) {
        String result = null;
        V1ObjectMeta metadata = pod.getMetadata();
        if (metadata != null) {
            Map<String, String> labels = metadata.getLabels();
            if (labels != null) {
                result = labels.get(label);
            }
        }
        return result;
    }

    public String getNamespace() {
        String result = null;
        V1ObjectMeta metadata = this.pod.getMetadata();
        if (metadata != null) {
            result = metadata.getNamespace();
        }
        return result;
    }

    public boolean isReady() {
        boolean result = false;
        V1PodStatus podStatus = pod.getStatus();
        if (podStatus != null &&  podStatus.getContainerStatuses() != null) {
            for (V1ContainerStatus status : podStatus.getContainerStatuses()) {
                if (status != null && "reaper".equalsIgnoreCase(status.getName())) {
                    result = status.isReady();
                }
            }
        }
        return result;
    }
}