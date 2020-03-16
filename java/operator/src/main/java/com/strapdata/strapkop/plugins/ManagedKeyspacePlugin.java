package com.strapdata.strapkop.plugins;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.ManagedKeyspace;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
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
    public void syncKeyspaces(final CqlKeyspaceManager cqlKeyspaceManager, final DataCenter dataCenter) {
        for(ManagedKeyspace managedKeyspace : dataCenter.getSpec().getManagedKeyspaces()) {
            if (!Strings.isNullOrEmpty(managedKeyspace.getKeyspace())) {
                cqlKeyspaceManager.addIfAbsent(dataCenter, managedKeyspace.getKeyspace(), () -> new CqlKeyspace()
                        .withName(managedKeyspace.getKeyspace())
                        .withRf(managedKeyspace.getRf())
                );
            }
        }
    }

    @Override
    public void syncRoles(final CqlRoleManager cqlRoleManager, final DataCenter dataCenter) {
        for(ManagedKeyspace managedKeyspace : dataCenter.getSpec().getManagedKeyspaces()) {
            if (!Strings.isNullOrEmpty(managedKeyspace.getRole())) {
                cqlRoleManager.addIfAbsent(dataCenter, managedKeyspace.getRole(), () -> new CqlRole()
                        .withUsername(managedKeyspace.getRole())
                        .withSecretNameProvider(dc -> {
                            return Strings.isNullOrEmpty(managedKeyspace.getSecretName()) ?
                                    OperatorNames.clusterChildObjectName("%s-keyspaces", dc) : managedKeyspace.getSecretName();
                        })
                        .withSecretKey(Strings.isNullOrEmpty(managedKeyspace.getSecretKey()) ? managedKeyspace.getRole() : managedKeyspace.getSecretKey())
                        .withApplied(false)
                        .withSuperUser(managedKeyspace.getSuperuser())
                        .withLogin(managedKeyspace.getLogin())
                        .withGrantStatements(managedKeyspace.getGrantStatements())
                );
            }
        }
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenter
     */
    @Override
    public Single<Boolean> reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        return Single.just(false);
    }

    /**
     * Call when deleting the elassandra datacenter
     *
     * @param dataCenter
     */
    @Override
    public Single<Boolean> delete(DataCenter dataCenter) throws ApiException {
        return Single.just(false);
    }


    @Override
    public boolean isActive(final DataCenter dataCenter) {
        return true;
    }

}
