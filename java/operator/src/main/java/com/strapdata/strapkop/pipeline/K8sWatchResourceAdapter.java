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

package com.strapdata.strapkop.pipeline;


import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import okhttp3.Call;

import java.lang.reflect.Type;
import java.util.Collection;

public abstract class K8sWatchResourceAdapter<ResourceT, ResourceListT, K> {
    public String getName() {
        return getResourceType().getTypeName();
    }
    public abstract Type getResourceType();
    public abstract Type getResourceListType();
    public abstract Call createListApiCall(final Boolean watch, final String resourceVersion) throws ApiException;
    public abstract K getKey(final ResourceT resource);
    public abstract V1ObjectMeta getMetadata(final ResourceT resource);
    public abstract Collection<? extends ResourceT> getListItems(final ResourceListT list);
    public abstract V1ListMeta getListMetadata(final ResourceListT list);
}
