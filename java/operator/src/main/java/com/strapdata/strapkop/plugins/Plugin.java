package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.kubernetes.client.ApiException;
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

    default boolean reconcileOnParkState(){
        return false;
    };

    /**
     * Add/Remove keyspaces to/from the cqlKeyspaceManager for the dataCenter
     */
    default void syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {

    }

    /**
     * Add/Remove roles to/from the cqlRoleManager for the dataCenter
     */
    default void syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {

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
