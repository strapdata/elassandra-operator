package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.util.*;

/**
 * Manage role creation, update and suppression of default cassandra account
 *
 * What this class does :
 * - retrieve credentials from the cluster secret
 * - create and update the managed roles (strapkop, admin, reaper...)
 * - update the .status.credentialsStatus of the datacenter objects.
 * - kubernetes-style reconciliation of cql credentials
 */
@Singleton
public class CqlCredentialsManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CqlCredentialsManager.class);
    
    private final CqlConnectionManager cqlConnectionManager;
    private final CoreV1Api coreApi;
    
    public final static CqlCredentials defaultCredentials = new CqlCredentials()
            .setUsername("cassandra")
            .setPassword("cassandra");
    
    // Map of managed cql users -> superuser boolean
    public final static Map<String, Boolean> superuserMap = ImmutableMap.of(
            "strapkop", true,
            "admin", true,
            "reaper", false
    );
    
    public CqlCredentialsManager(CqlConnectionManager cqlConnectionManager, CoreV1Api coreApi) {
        this.cqlConnectionManager = cqlConnectionManager;
        this.coreApi = coreApi;
    }
    
    /**
     * Get the current credentials that strapkop should use to connect this dc.
     * Warning : this is a costly operation as it require to read from kubernetes secret
     *
     * @return the Credentials object
     * @throws StrapkopException if the credentials to use can't be determined from the credentials status
     */
    public CqlCredentials getCurrentCredentials(DataCenter dataCenter) throws StrapkopException, ApiException {
        
        if (!dataCenter.getStatus().getCredentialsStatus().getUnknown() &&
                dataCenter.getStatus().getCredentialsStatus().getManaged()) {
            return loadCredentials(dataCenter, "strapkop");
        } else if (!dataCenter.getStatus().getCredentialsStatus().getUnknown() &&
                dataCenter.getStatus().getCredentialsStatus().getDefaultRole()) {
            return defaultCredentials;
        } else {
            throw new StrapkopException(String.format("strapkop doesn't know which cql credentials to use for dc=%s",
                    dataCenter.getMetadata().getName()));
        }
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
        
        // trigger a special checkup if the credentials status are unknown
        if (dataCenter.getStatus().getCredentialsStatus().getUnknown()) {
            checkUnknownStatus(dataCenter);
        }
        
        // abort if cql connection has not been established... we will retry later
        if (!Objects.equals(dataCenter.getStatus().getCqlStatus(), CqlStatus.ESTABLISHED) || cqlConnectionManager.getConnection(dataCenter) == null) {
            return;
        }
        
        // now we are sure authentication is required and cql connection has been set
        logger.info("reconcile credentials for {}", dataCenter.getMetadata().getName());
        
        try {
            
            // either if the credentials are managed...
            if (dataCenter.getStatus().getCredentialsStatus().getManaged()) {
                
                // in that case we removeConnection the default cassandra role if necessary
                if (dataCenter.getStatus().getCredentialsStatus().getDefaultRole()) {
                    removeDefaultRole(dataCenter);
                }
            } else {
                // otherwise we create the managed roles and close the default cql session
                createManagedRoles(dataCenter);
                cqlConnectionManager.removeConnection(dataCenter);
            }
            
        } catch (DriverException exception) {
            logger.error("error while creating/updating cql roles", exception);
        }
    }
    
    
    /**
     * Given that the status of credentials is not known, try to connect with cassandra and strapkop to update credentialStatus
     * @param dataCenter
     * @throws StrapkopException
     * @throws ApiException
     * @throws SSLException
     */
    private void checkUnknownStatus(DataCenter dataCenter) throws StrapkopException, ApiException, SSLException {
        logger.info("try to connect to dc={} with unknown credentials", dataCenter.getMetadata().getName());
    
        final CqlCredentials strapkopCredentials = loadCredentials(dataCenter, "strapkop");
    
        try {
            logger.info("try connecting to {} with default credentials", dataCenter.getMetadata().getName());
            cqlConnectionManager.updateConnection(dataCenter, defaultCredentials);
            logger.info("successfully connected to {} with default credentials", dataCenter.getMetadata().getName());
            dataCenter.getStatus().getCredentialsStatus().setDefaultRole(true);
        } catch (AuthenticationException e) {
            logger.info("failed to connect to {} with default credentials", dataCenter.getMetadata().getName(), e);
            dataCenter.getStatus().getCredentialsStatus().setDefaultRole(false);
            logger.info("successfully connected to {} with managed credentials", dataCenter.getMetadata().getName());
        }
        
        try {
            logger.info("try connecting to {} with strapkop credentials", dataCenter.getMetadata().getName());
            cqlConnectionManager.updateConnection(dataCenter, strapkopCredentials);
            logger.info("successfully connected to {} with managed credentials", dataCenter.getMetadata().getName());
            dataCenter.getStatus().getCredentialsStatus().setManaged(true);
        } catch (AuthenticationException e) {
            logger.info("failed to connect to {} with managed credentials", dataCenter.getMetadata().getName(), e);
            dataCenter.getStatus().getCredentialsStatus().setManaged(false);
        }
    
        dataCenter.getStatus().getCredentialsStatus().setUnknown(false);
        cqlConnectionManager.removeConnection(dataCenter);
    }
    
    /**
     * Create all managed roles with password found in the cluster secret
     *
     * @param dataCenter
     * @throws StrapkopException
     * @throws ApiException
     */
    private void createManagedRoles(DataCenter dataCenter) throws StrapkopException, ApiException {
        final Session session = Objects.requireNonNull(cqlConnectionManager.getConnection(dataCenter));
        final Map<String, CqlCredentials> credentials = loadAllCredentials(dataCenter);
        for (CqlCredentials cred : credentials.values()) {
            logger.info("creating role {} for {}", cred.getUsername(), dataCenter.getMetadata().getName());
            createOrUpdateRole(cred, session, superuserMap.get(cred.getUsername()));
        }
        dataCenter.getStatus().getCredentialsStatus().setManaged(true);
    }
    
    /**
     * Remove default cassandra role
     *
     * @param dataCenter
     */
    private void removeDefaultRole(DataCenter dataCenter) {
        final Session session = Objects.requireNonNull(cqlConnectionManager.getConnection(dataCenter));
        logger.info("Dropping default role cassandra for {}", dataCenter.getMetadata().getName());
        session.execute("DROP ROLE IF EXISTS cassandra");
        dataCenter.getStatus().getCredentialsStatus().setDefaultRole(false);
    }
    
    /**
     * Create or update a role
     *
     * @param credentials
     * @param session
     * @throws StrapkopException
     */
    private void createOrUpdateRole(CqlCredentials credentials, Session session, boolean superuser) throws StrapkopException {
        if (credentials.getUsername().matches(".*[\"\';].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra username %s", credentials.getUsername()));
        }
        if (credentials.getPassword().matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", credentials.getUsername()));
        }
        
        // create role if not exists, then alter... so this is completely idempotent and can even update password, although it might not be optimized
        session.execute(String.format("CREATE ROLE IF NOT EXISTS %s with SUPERUSER = %b AND LOGIN = true and PASSWORD = '%s'", credentials.getUsername(), superuser, credentials.getPassword()));
        session.execute(String.format("ALTER ROLE %s WITH SUPERUSER = %b AND LOGIN = true AND PASSWORD = '%s'", credentials.getUsername(), superuser, credentials.getPassword()));
    }
    
    
    /**
     * Load a credentials by reading in the cluster secret
     *
     * @param dataCenter
     * @param username
     * @return
     * @throws StrapkopException
     * @throws ApiException
     */
    public CqlCredentials loadCredentials(final DataCenter dataCenter, final String username) throws StrapkopException, ApiException {
        return loadCredentialsList(dataCenter, Collections.singleton(username)).get(username);
    }
    
    /**
     * Load all managed credentials
     *
     * @param dataCenter
     * @return
     * @throws ApiException
     * @throws StrapkopException
     */
    public Map<String, CqlCredentials> loadAllCredentials(final DataCenter dataCenter) throws ApiException, StrapkopException {
        return loadCredentialsList(dataCenter, superuserMap.keySet());
    }
    
    private Map<String, CqlCredentials> loadCredentialsList(final DataCenter dataCenter, Collection<String> userList) throws ApiException, StrapkopException {
        
        final String secretName = OperatorNames.clusterSecret(dataCenter);
        
        final V1Secret secret = coreApi.readNamespacedSecret(secretName,
                dataCenter.getMetadata().getNamespace(),
                null,
                null,
                null);
        
        final Map<String, CqlCredentials> credentials = new HashMap<>();
        for (String user : userList) {
            byte[] password = secret.getData().get(String.format("cassandra.%s_password", user));
            if (password == null) {
                throw new StrapkopException(String.format("secret %s does not contain password for user %s", secretName, user));
            }
            credentials.put(user, new CqlCredentials()
                    .setUsername(user)
                    .setPassword(new String(password)));
        }
        
        return credentials;
    }
}
