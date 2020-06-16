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

package com.strapdata.strapkop.model.fabric8.datacenter;

import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

// see https://github.com/fabric8io/kubernetes-client/tree/master/kubernetes-examples/src/main/java/io/fabric8/kubernetes/examples/crds
@ToString
@Getter
@Setter
public class DataCenter extends CustomResource {
    private DataCenterSpec spec;
    private DataCenterStatus status;

    @Override
    public ObjectMeta getMetadata() { return super.getMetadata(); }
}
