package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
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
    Single<Boolean> reconcile(final DataCenter dataCenter) throws ApiException, StrapkopException, IOException;

    /**
     * Call when datacenter is reconcilied after a start or scale up/down
     */
    Completable reconciled(final DataCenter dataCenter) throws ApiException, StrapkopException;


    /**
     * Call when deleting the elassandra datacenter
     */
    Single<Boolean> delete(final DataCenter dataCenter) throws ApiException;
}
