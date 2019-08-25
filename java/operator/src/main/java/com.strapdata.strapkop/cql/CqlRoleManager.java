package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.Authentication;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.ssl.AuthorityManager;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.util.Collection;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Manage cassandra role creation and update password from k8s secrets.
 *
 * What this class does :
 * - retrieve credentials from the cluster secret
 * - create and update the managed roles (strapkop, admin, reaper...)
 *
 * TODO: update password when k8s secret is updated.
 */
@Singleton
public class CqlRoleManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlRoleManager.class);

    // Map of managed cql users -> superuser boolean
    public final static Map<String, CqlRole> DEFAULT_MANAGED_ROLES = ImmutableMap.of(
            "cassandra", new CqlRole().withUsername("cassandra").withSuperUser(true).withPassword("cassandra"),
            "admin", new CqlRole().withUsername("admin").withSuperUser(true),
            "strapkop", new CqlRole().withUsername("strapkop").withSuperUser(true)
    );

    final CqlConnectionManager cqlConnectionManager;
    final KeyspaceReplicationManager keyspaceReplicationManager;
    final CoreV1Api coreApi;
    final AppsV1Api appsApi;
    final CustomObjectsApi customObjectsApi;
    final K8sResourceUtils k8sResourceUtils;
    final AuthorityManager authorityManager;

    // role cache for managed datacenters, key=namespace/clusterName/dcName/roleName
    private final NavigableMap<String, CqlRole> managedRoles = new ConcurrentSkipListMap<>();
    
    public CqlRoleManager(CqlConnectionManager cqlConnectionManager,
                          KeyspaceReplicationManager keyspaceReplicationManager,
                          CoreV1Api coreApi,
                          AppsV1Api appsApi,
                          CustomObjectsApi customObjectsApi,
                          K8sResourceUtils k8sResourceUtils,
                          AuthorityManager authorityManager) {
        this.cqlConnectionManager = cqlConnectionManager;
        this.keyspaceReplicationManager = keyspaceReplicationManager;
        this.coreApi = coreApi;
        this.appsApi = appsApi;
        this.customObjectsApi = customObjectsApi;
        this.k8sResourceUtils = k8sResourceUtils;
        this.authorityManager = authorityManager;
    }

    public CqlRole addRole(DataCenter dataCenter, String roleName, boolean superUser, Map<String, Integer> keyspaceRfMap, Collection<String> grantStatements) throws ApiException, StrapkopException {
        if (roleName.matches(".*[\"\';].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra username %s", roleName));
        }
        CqlRole cqlRole = new CqlRole().withUsername(roleName).withSuperUser(superUser)
                .withKeyspaceRf(keyspaceRfMap)
                .withGrantStatements(grantStatements);
        managedRoles.put(roleKey(dataCenter, cqlRole), cqlRole);
        return cqlRole;
    }

    public CqlRole getRole(final DataCenter dataCenter, String roleName) {
        return managedRoles.get(roleKey(dataCenter, roleName));
    }

    private String roleKey(DataCenter dataCenter, String roleName) {
        return dataCenter.getMetadata().getNamespace()+"/"+dataCenter.getSpec().getClusterName()+"/"+dataCenter.getMetadata().getName()+"/"+roleName;
    }

    private String roleKey(DataCenter dataCenter, CqlRole cqlRole) {
        return roleKey(dataCenter, cqlRole.getUsername());
    }

    /**
     * Replace the default cassandra password by the one stored in k8s secret if needed.
     * @param dataCenter
     * @param session
     * @throws ApiException
     * @throws StrapkopException
     */
    public void updateCassandraPassword(DataCenter dataCenter, Session session) throws ApiException, StrapkopException {
        CqlRole cassandraRole = managedRoles.get(roleKey(dataCenter, "cassandra"));
        if (cassandraRole.getPassword().equals("cassandra"))
            cassandraRole.loadPassword(dataCenter, getSecret(dataCenter));
        if (!cassandraRole.isApplied())
            cassandraRole.updatePassword(session);
    }
    
    /**
     * Idempotent credentials reconciliation
     *
     * @param dataCenter
     * @throws ApiException
     * @throws StrapkopException
     */
    public void reconcileCredentials(DataCenter dataCenter) throws ApiException, StrapkopException, SSLException {
        
        // abort if the dc is not running yet
        if (!dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING)) {
            return;
        }
        
        Objects.requireNonNull(dataCenter.getSpec().getAuthentication());
        // abort now if there is no authentication on this cluster
        if (Objects.equals(dataCenter.getSpec().getAuthentication(), Authentication.NONE)) {
            return;
        }

        // abort if cql connection has not been established... we will retry later
        if (!Objects.equals(dataCenter.getStatus().getCqlStatus(), CqlStatus.ESTABLISHED)) {
            return;
        }
        final Session session = cqlConnectionManager.getConnection(dataCenter);
        if (session == null)
            return;

        for(CqlRole cqlRole: DEFAULT_MANAGED_ROLES.values()) {
            managedRoles.putIfAbsent(cqlRole.username, cqlRole);
        }
        if (dataCenter.getSpec().getReaperSupport().equals(true)) {
            managedRoles.putIfAbsent(CqlRole.REAPER_ROLE.username, CqlRole.REAPER_ROLE);
        }

        // now we are sure authentication is required and cql connection has been set
        logger.info("reconcile roles for cluster={} dc={}", dataCenter.getSpec().getClusterName(), dataCenter.getMetadata().getName());
        boolean hasAppliedStrapkopRole = false;
        V1Secret secret = null;
        for(CqlRole cqlRole : managedRoles.values()) {
            if (cqlRole.getUsername().equals("cassandra") || cqlRole.isApplied())
                continue;
            try {
                if (secret == null)
                    secret = getSecret(dataCenter);
                cqlRole.loadPassword(dataCenter, secret).createOrUpdateRole(dataCenter, session, this);
            } catch (Exception ex) {
                logger.error("Cannot load password or apply for role={} in cluster={} dc={}",cqlRole.getUsername(), dataCenter.getSpec().getClusterName(), dataCenter.getMetadata().getName());
            }
            if (cqlRole.getUsername().equals("strapkop"))
                hasAppliedStrapkopRole = true;
        }
        if (hasAppliedStrapkopRole) {
            // should trigger a reconnection with the strapkop role.
            cqlConnectionManager.removeConnection(dataCenter);
        }
    }

    private V1Secret getSecret(DataCenter dataCenter) throws ApiException {
        final String secretName = OperatorNames.clusterSecret(dataCenter);
        return coreApi.readNamespacedSecret(secretName,
                dataCenter.getMetadata().getNamespace(),
                null,
                null,
                null);
    }
}
