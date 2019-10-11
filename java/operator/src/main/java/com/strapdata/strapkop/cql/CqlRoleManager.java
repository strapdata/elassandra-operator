package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manage cassandra role creation and update password from k8s secrets, and create associated CQL permission.
 * Role reconciliation must be made after Keyspace reconciliation.
 *
 *
 * TODO: update password when k8s secret is updated.
 */
@Singleton
public class CqlRoleManager extends AbstractManager<CqlRole> {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlRoleManager.class);

    final CoreV1Api coreApi;
    final PluginRegistry pluginRegistry;

    public CqlRoleManager(final CqlConnectionManager cqlConnectionManager,
                          final CoreV1Api coreApi,
                          final PluginRegistry pluginRegistry) {
        super(cqlConnectionManager);
        this.coreApi = coreApi;
        this.pluginRegistry = pluginRegistry;
    }
    
    /**
     * Idempotent credentials reconciliation
     *
     * @param dataCenter
     * @throws ApiException
     * @throws StrapkopException
     */
    public void reconcileRole(DataCenter dataCenter) throws ApiException, StrapkopException, SSLException {
        if (Authentication.NONE.equals(dataCenter.getSpec().getAuthentication()))
            return;

        addIfAbsent(dataCenter, CqlRole.CASSANDRA_ROLE.username, () -> CqlRole.CASSANDRA_ROLE.duplicate());
        addIfAbsent(dataCenter, CqlRole.ADMIN_ROLE.username, () -> CqlRole.ADMIN_ROLE.duplicate());
        addIfAbsent(dataCenter, CqlRole.STRAPKOP_ROLE.username, () -> CqlRole.STRAPKOP_ROLE.duplicate());

        for(Plugin plugin : pluginRegistry.plugins()) {
            if (plugin.isActive(dataCenter))
                plugin.syncRoles(this, dataCenter);
        }


        final Session session = cqlConnectionManager.getConnection(dataCenter);
        if (session == null)
            return;

        // load password when secret is available
        Map<String, V1Secret> secrets = new HashMap<>();
        for(CqlRole role : get(dataCenter).values()) {
            if (role.getPassword() == null) {
                V1Secret secret = secrets.computeIfAbsent(role.secretNameProvider.apply(dataCenter), k -> {
                    return getSecret(coreApi, dataCenter, k);
                });
                if (secret != null)
                    role.loadPassword(secret);
            }
        }

        // now we are sure authentication is required and cql connection has been set
        logger.info("reconcile roles for dc={}", dataCenter.getMetadata().getName());
        boolean hasAppliedStrapkopRole = false;
        for(CqlRole role : get(dataCenter).values()) {
            if (role.isApplied())
                continue;
            try {
                role.createOrUpdateRole(dataCenter, session);
                if (role.getUsername().equals(CqlRole.STRAPKOP_ROLE.username))
                    hasAppliedStrapkopRole = true;
            } catch (Exception ex) {
                logger.error("Cannot load password or apply for role="+role.getUsername()+" in dc="+dataCenter.getMetadata().getName(), ex);
            }
        }
        if (hasAppliedStrapkopRole) {
            // should trigger a reconnection with the strapkop role.
            cqlConnectionManager.removeConnection(dataCenter);
        }
    }

    public static V1Secret getSecret(CoreV1Api coreApi, DataCenter dataCenter, String secretName) {
        try {
            return coreApi.readNamespacedSecret(secretName,
                    dataCenter.getMetadata().getNamespace(),
                    null,
                    null,
                    null);
        } catch(ApiException e) {
            if (e.getCode() != 404) {
                logger.error("Secret name="+secretName+" cannot be loaded", e);
            } else {
                logger.warn("Secret name=" + secretName + " not found");
            }
            return null;
        }
    }
}
