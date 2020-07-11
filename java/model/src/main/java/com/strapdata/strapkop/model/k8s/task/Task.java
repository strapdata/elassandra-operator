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

package com.strapdata.strapkop.model.k8s.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.Date;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@Getter
@ToString
@Schema(name="Task", description = "Elassandra task CRD")
public class Task {

    public static final String NAME = "elassandratask";
    public static final String PLURAL = "elassandratasks";
    public static final String VERSION = "v1beta1";
    public static final String SCOPE = "Namespaced";
    public static final String KIND = "ElassandraTask";

    @SerializedName("apiVersion")
    @Expose
    private String apiVersion = StrapdataCrdGroup.GROUP + "/" + VERSION;

    @SerializedName("kind")
    @Expose
    private String kind = KIND;

    @SerializedName("metadata")
    @Expose
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    @Expose
    private TaskSpec spec;

    @SerializedName("status")
    @Expose
    private TaskStatus status = new TaskStatus().withStartDate(new Date()).setPhase(TaskPhase.WAITING);

    public static Task fromDataCenter(String name, DataCenter dc) {
        return new Task()
                .setMetadata(new V1ObjectMeta().name(name)
                        .namespace(dc.getMetadata().getNamespace())
                        .labels(OperatorLabels.datacenter(dc))
                )
                .setSpec(new TaskSpec()
                        .setCluster(dc.getSpec().getClusterName())
                        .setDatacenter(dc.getSpec().getDatacenterName())
                );
    }

    public String id() {
        return metadata.getNamespace() + "/" + metadata.getName();
    }

    @JsonIgnore
    public String getParent() {
        return getMetadata().getLabels().get(OperatorLabels.PARENT);
    }

}
