package com.strapdata.strapkop.plugins;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.StrapkopException;
import io.kubernetes.client.ApiException;

public interface Plugin {

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
    default void reconcile(final DataCenter dataCenter) throws ApiException, StrapkopException {

    }

    /**
     * Call when deleting the elassandra datacenter
     */
    default void delete(final DataCenter dataCenter) throws ApiException {

    }
}
