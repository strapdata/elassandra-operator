package com.strapdata.strapkop.reconcilier;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.DriverException;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.CredentialsStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.cql.CqlCredentials;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Singleton
public class CredentialsReconcilier extends Reconcilier<DataCenter> {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialsReconcilier.class);
    
    private final CqlConnectionManager cqlConnectionManager;
    private final CoreV1Api coreApi;
    private K8sResourceUtils k8sResourceUtils;
    
    
    private final static CqlCredentials defaultCredentials = new CqlCredentials()
            .setUsername("cassandra")
            .setPassword("cassandra");
    
    public CredentialsReconcilier(CqlConnectionManager cqlConnectionManager, CoreV1Api coreApi, K8sResourceUtils k8sResourceUtils) {
        this.cqlConnectionManager = cqlConnectionManager;
        this.coreApi = coreApi;
        this.k8sResourceUtils = k8sResourceUtils;
    }
    
    @Override
    public void reconcile(DataCenter dataCenter) throws ApiException, StrapkopException, SSLException {
        
        // temporary fix
        dataCenter = k8sResourceUtils.freshenDataCenter(dataCenter);
        
        try {
    
            logger.info("reconcile credentials for {}", dataCenter.getMetadata().getName());
    
            final List<CqlCredentials> credentials = loadCredentialsFromSecret(dataCenter, OperatorNames.clusterSecret(dataCenter));
            final CqlCredentials strapkopCredentials = credentials.stream().filter(c -> Objects.equals("strapkop", c.getUsername())).findFirst().get();
    
            Session session;
            try {
                logger.info("try connecting to {} with default credentials", dataCenter.getMetadata().getName());
                session = cqlConnectionManager.add(dataCenter, defaultCredentials);
                logger.info("successfully connected to {} with default credentials", dataCenter.getMetadata().getName());
    
                for (CqlCredentials cred : credentials) {
                    logger.info("creating role {} for {}", cred.getUsername(), dataCenter.getMetadata().getName());
                    createRole(cred, session);
                }
                
                logger.info("Connecting to {} with new credentials and closing default connection", dataCenter.getMetadata().getName());
                session = cqlConnectionManager.add(dataCenter, strapkopCredentials);
    
                logger.info("Dropping default role cassandra for {}", dataCenter.getMetadata().getName());
                session.execute("DROP ROLE cassandra");
                session.execute(String.format(
                        "ALTER KEYSPACE system_auth WITH replication = {'class': 'NetworkTopologyStrategy', '%s': %d};",
                        dataCenter.getSpec().getDatacenterName(), 1)); // TODO: find a smart way to set the RF map
            }
            catch (AuthenticationException e) {
                logger.info("failed to connect to {} with default credentials, trying to connect with managed credentials", dataCenter.getMetadata().getName(), e);
                cqlConnectionManager.add(dataCenter, strapkopCredentials);
                logger.info("successfully connected to {} with managed credentials", dataCenter.getMetadata().getName());
            }
            
            dataCenter.getStatus().setCredentialsStatus(CredentialsStatus.MANAGED);
            dataCenter.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
            dataCenter.getStatus().setCqlErrorMessage("");
            k8sResourceUtils.updateDataCenterStatus(dataCenter);
    
    
            logger.info("reconciled credentials for {}", dataCenter.getMetadata().getName());
        }
        catch (DriverException e) {
            logger.error("Driver exception while reconciling credentials for {}", dataCenter.getMetadata().getName(), e);
            dataCenter.getStatus().setCredentialsStatus(CredentialsStatus.UNKNOWN);
            dataCenter.getStatus().setCqlStatus(CqlStatus.ERRORED);
            dataCenter.getStatus().setCqlErrorMessage(e.getMessage());
            k8sResourceUtils.updateDataCenterStatus(dataCenter);
        }
    }
    
    private void createRole(CqlCredentials credentials, Session session) throws StrapkopException {
        if (credentials.getUsername().matches(".*[\"\';].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra username %s", credentials.getUsername()));
        }
        if (credentials.getPassword().matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", credentials.getUsername()));
        }
        session.execute(String.format("CREATE ROLE IF NOT EXISTS %s with SUPERUSER = true AND LOGIN = true and PASSWORD = '%s'", credentials.getUsername(), credentials.getPassword()));
    }
    
    private List<CqlCredentials> loadCredentialsFromSecret(final DataCenter dataCenter, final String secretName) throws ApiException, StrapkopException {
        final V1Secret secret = coreApi.readNamespacedSecret(secretName,
                dataCenter.getMetadata().getNamespace(),
                null,
                null,
                null);

    
        final byte[] adminPassword = secret.getData().get("admin_password");
        final byte[] strapkopPassword = secret.getData().get("strapkop_password");

        if (adminPassword == null || strapkopPassword == null) {
            throw new StrapkopException(String.format("secret %s does not contain the correct passwords fields", secretName));
        }
    
        final List<CqlCredentials> credentials = new ArrayList<>();
        credentials.add(new CqlCredentials().setUsername("strapkop").setPassword(new String(strapkopPassword)));
        credentials.add(new CqlCredentials().setUsername("admin").setPassword(new String(adminPassword)));
    
        return credentials;
    }
}
