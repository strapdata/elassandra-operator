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

package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import io.kubernetes.client.openapi.ApiException;
import io.reactivex.Completable;
import io.reactivex.Single;

import java.io.IOException;

public interface Plugin {

    /**
     * Check the plugin is active in the specified datacenter.
     * @param dataCenter
     * @return
     */
    boolean isActive(final DataCenter dataCenter);

    /**
     * Add/Remove keyspaces to/from the cqlKeyspaceManager for the dataCenter
     */
    default Completable syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        return Completable.complete();
    }

    /**
     * Add/Remove roles to/from the cqlRoleManager for the dataCenter
     */
    default Completable syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) throws ApiException {
        return Completable.complete();
    }

    /**
     * Call on each reconciliation
     */
    Single<Boolean> reconcile(final DataCenterUpdateAction dataCenterUpdateAction) throws ApiException, StrapkopException, IOException;

    /**
     * Call when datacenter is reconcilied after a start or scale up/down
     */
    Completable reconciled(final DataCenter dataCenter) throws ApiException, StrapkopException;


    /**
     * Call when deleting the elassandra datacenter
     */
    Single<Boolean> delete(final DataCenter dataCenter) throws ApiException;
}
