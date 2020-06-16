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

package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

@Data
@With
@AllArgsConstructor
@NoArgsConstructor
@Schema(name="DataCenter", description="Elassandra DataCenter CRD")
public class DataCenter {

    public static final String NAME = "elassandradatacenter";
    public static final String PLURAL = "elassandradatacenters";
    public static final String VERSION = "v1";
    public static final String SCOPE = "Namespaced";
    public static final String KIND = "ElassandraDatacenter";

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
    private DataCenterSpec spec;

    @SerializedName("status")
    @Expose
    private DataCenterStatus status = new DataCenterStatus();

    public String id() {
        return metadata.getName()+"/"+metadata.getNamespace();
    }
}
