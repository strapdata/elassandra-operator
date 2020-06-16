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

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.ManagedKeyspace;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateAction;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;

import javax.inject.Singleton;

/**
 * Manage user keyspaces and roles
 */
@Singleton
public class ManagedKeyspacePlugin extends AbstractPlugin {

    public ManagedKeyspacePlugin(final ApplicationContext context,
                                 K8sResourceUtils k8sResourceUtils,
                                 AuthorityManager authorityManager,
                                 CoreV1Api coreApi,
                                 AppsV1Api appsApi,
                                 OperatorConfig operatorConfig,
                                 MeterRegistry meterRegistry) {
        super(context, k8sResourceUtils, authorityManager, coreApi, appsApi, operatorConfig, meterRegistry);
    }

    @Override
    public Completable syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        for(ManagedKeyspace managedKeyspace : dataCenter.getSpec().getManagedKeyspaces()) {
            if (!Strings.isNullOrEmpty(managedKeyspace.getKeyspace())) {
                cqlKeyspaceManager.addIfAbsent(dataCenter, managedKeyspace.getKeyspace(), () -> new CqlKeyspace()
                        .withName(managedKeyspace.getKeyspace())
                        .withRf(managedKeyspace.getRf())
                );
            }
        }
        return Completable.complete();
    }

    @Override
    public Completable syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {

        for(ManagedKeyspace managedKeyspace : dataCenter.getSpec().getManagedKeyspaces()) {
            if (!Strings.isNullOrEmpty(managedKeyspace.getRole())) {
                cqlRoleManager.addIfAbsent(dataCenter, managedKeyspace.getRole(), () -> new CqlRole()
                        .withUsername(managedKeyspace.getRole())
                        .withSecretNameProvider(dc -> {
                            return Strings.isNullOrEmpty(managedKeyspace.getSecretName()) ?
                                    OperatorNames.clusterChildObjectName("%s-keyspaces", dc) : managedKeyspace.getSecretName();
                        })
                        .withSecretKey(Strings.isNullOrEmpty(managedKeyspace.getSecretKey()) ? managedKeyspace.getRole() : managedKeyspace.getSecretKey())
                        .withReconcilied(false)
                        .withSuperUser(managedKeyspace.getSuperuser())
                        .withLogin(managedKeyspace.getLogin())
                        .withGrantStatements(managedKeyspace.getGrantStatements())
                );
            }
        }
        return Completable.complete();
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenterUpdateAction
     */
    @Override
    public Single<Boolean> reconcile(DataCenterUpdateAction dataCenterUpdateAction) throws StrapkopException {
        return Single.just(false);
    }

    /**
     * Call when deleting the elassandra datacenter
     *
     * @param dataCenter
     */
    @Override
    public Single<Boolean> delete(DataCenter dataCenter) {
        return Single.just(false);
    }


    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return true;
    }

}
