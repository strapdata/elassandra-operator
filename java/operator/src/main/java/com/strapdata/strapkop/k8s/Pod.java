/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.k8s;

import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;

import java.util.Map;

public class Pod {
    private final V1Pod pod;
    private final String containerName;

    public Pod(V1Pod pod, String containerName) {
        this.containerName = containerName;
        this.pod = pod;
    }

    public String getName() {
        return pod.getMetadata().getName();
    }

    public String getNamespace() {
        return this.pod.getMetadata().getNamespace();
    }

    public String getClusterName() {
        return extractLabel(this.pod, OperatorLabels.CLUSTER);
    }

    public String getDatacenter() { return extractLabel(this.pod, OperatorLabels.DATACENTER); }

    public String getParent()  {
        return extractLabel(this.pod, OperatorLabels.PARENT);
    }

    public String getRack() {
        return extractLabel(this.pod, OperatorLabels.RACK);
    }

    public Integer getRackIndex() { return Integer.parseInt(extractLabel(this.pod, OperatorLabels.RACKINDEX)); }

    public String id() {
        return pod.getMetadata().getName() + "/" + pod.getMetadata().getNamespace();
    }

    public String getElassandraDatacenter() {
        return extractLabel(this.pod, OperatorLabels.PARENT);
    }

    public static String extractLabel(V1Pod pod, String label) {
        V1ObjectMeta metadata = pod.getMetadata();
        if (metadata != null) {
            Map<String, String> labels = metadata.getLabels();
            if (labels != null) {
                return labels.get(label);
            }
        }
        return null;
    }


    public boolean isReady() {
        V1PodStatus podStatus = pod.getStatus();
        if (podStatus != null &&  podStatus.getContainerStatuses() != null) {
            for (V1ContainerStatus status : podStatus.getContainerStatuses()) {
                if (status != null && containerName.equalsIgnoreCase(status.getName())) {
                    return status.getReady();
                }
            }
        }
        return false;
    }
}