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

package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import io.kubernetes.client.openapi.ApiException;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import javax.inject.Inject;
import java.util.Map;

@Controller("/datacenter")
public class DatacenterController {

    @Inject
    CqlKeyspaceManager cqlKeyspaceManager;

    @Inject
    CqlRoleManager cqlRoleManager;

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_keyspace", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlKeyspace> managedKeyspaces(String namespace, String cluster, String datacenter) throws ApiException {
        return cqlKeyspaceManager.get(namespace, cluster, datacenter);
    }

    @Get(value = "/{namespace}/{cluster}/{datacenter}/_role", produces = MediaType.APPLICATION_JSON)
    public Map<String, CqlRole> managedRoles(String namespace, String cluster, String datacenter) throws ApiException {
        return cqlRoleManager.get(namespace, cluster, datacenter);
    }
}