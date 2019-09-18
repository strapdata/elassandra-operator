package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;

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

    public CqlRoleManager(CqlConnectionManager cqlConnectionManager,
                          CoreV1Api coreApi) {
        super(cqlConnectionManager);
        this.coreApi = coreApi;
    }

    public CqlRole addRole(DataCenter dataCenter, CqlRole role) throws ApiException, StrapkopException {
        if (role.username.matches(".*[\"\';].*"))
            throw new StrapkopException(String.format("invalid character in cassandra username %s", role.username));
        add(dataCenter, role.username, role);
        return role;
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

        addIfAbsent(dataCenter, CqlRole.CASSANDRA_ROLE.username, CqlRole.CASSANDRA_ROLE.duplicate());
        addIfAbsent(dataCenter, CqlRole.ADMIN_ROLE.username, CqlRole.ADMIN_ROLE.duplicate());
        addIfAbsent(dataCenter, CqlRole.STRAPKOP_ROLE.username, CqlRole.STRAPKOP_ROLE.duplicate());
        addIfAbsent(dataCenter, CqlRole.REAPER_ROLE.username, CqlRole.REAPER_ROLE.duplicate());

        // load password, even if we're not connected
        V1Secret secret = null;
        for(CqlRole role : get(dataCenter).values()) {
            if (role.getPassword() == null) {
                if (secret == null)
                    secret = getSecret(dataCenter);
                role.loadPassword(secret);
            }
        }

        final Session session = cqlConnectionManager.getConnection(dataCenter);
        if (session == null)
            return;

        // now we are sure authentication is required and cql connection has been set
        logger.info("reconcile roles for dc={}", dataCenter.getMetadata().getName());
        boolean hasAppliedStrapkopRole = false;
        for(CqlRole role : get(dataCenter).values()) {
            if (role.isApplied())
                continue;
            try {
                role.createOrUpdateRole(dataCenter, session);
            } catch (Exception ex) {
                logger.error("Cannot load password or apply for role="+role.getUsername()+" in dc="+dataCenter.getMetadata().getName(), ex);
            }
            if (role.getUsername().equals(CqlRole.STRAPKOP_ROLE.username))
                hasAppliedStrapkopRole = true;
        }
        if (hasAppliedStrapkopRole) {
            // should trigger a reconnection with the strapkop role.
            cqlConnectionManager.removeConnection(dataCenter);
        }
    }

    public V1Secret getSecret(DataCenter dataCenter) throws ApiException {
        return getSecret(dataCenter, this.coreApi);
    }

    public static V1Secret getSecret(DataCenter dataCenter, final CoreV1Api coreApi) throws ApiException {
        final String secretName = OperatorNames.clusterSecret(dataCenter);
        return coreApi.readNamespacedSecret(secretName,
                dataCenter.getMetadata().getNamespace(),
                null,
                null,
                null);
    }
}
