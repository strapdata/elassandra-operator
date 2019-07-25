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
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.net.ssl.SSLException;

@Singleton
public class CredentialsReconcilier {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialsReconcilier.class);
    
    private final CqlConnectionManager cqlConnectionManager;
    private final CoreV1Api coreApi;
    private final CustomObjectsApi customObjectsApi;
    
    private final static CqlCredentials defaultCredentials = new CqlCredentials()
            .setUsername("cassandra")
            .setPassword("cassandra");
    
    public CredentialsReconcilier(CqlConnectionManager cqlConnectionManager, CoreV1Api coreApi, CustomObjectsApi customObjectsApi) {
        this.cqlConnectionManager = cqlConnectionManager;
        this.coreApi = coreApi;
        this.customObjectsApi = customObjectsApi;
    }
    
    public void reconcile(final DataCenter dataCenter) throws ApiException, StrapkopException, SSLException {
        
        // TODO: be idempotent
        
        try {
    
            logger.info("reconcile credentials for {}", dataCenter.getMetadata().getName());
    
            final CqlCredentials strapkopCredentials = loadCredentialsFromSecret(dataCenter,
                    OperatorNames.strapkopCredentials(dataCenter));
    
            final CqlCredentials adminCredentials = loadCredentialsFromSecret(dataCenter,
                    OperatorNames.adminCredentials(dataCenter));
    
            Session session;
            try {
                logger.info("try connecting to {} with default credentials", dataCenter.getMetadata().getName());
                session = cqlConnectionManager.add(dataCenter, defaultCredentials);
                logger.info("successfully connected to {} with default credentials", dataCenter.getMetadata().getName());
                
                logger.info("creating role strapkop for {}", dataCenter.getMetadata().getName());
                createRole(strapkopCredentials, session);
    
                logger.info("creating role admin for {}", dataCenter.getMetadata().getName());
                createRole(adminCredentials, session);
    
                logger.info("Connecting to {} with new credentials and closing default connection", dataCenter.getMetadata().getName());
                session = cqlConnectionManager.add(dataCenter, strapkopCredentials);
    
                logger.info("Dropping default role cassandra for {}", dataCenter.getMetadata().getName());
                session.execute("DROP ROLE cassandra");
                session.execute("ALTER KEYSPACE system_auth WITH replication = {'class': 'NetworkTopologyStrategy', ?: ?};",
                        dataCenter.getSpec().getDatacenterName(), 1); // TODO: find a smart way to set the RF map
            }
            catch (AuthenticationException e) {
                logger.info("failed to connect to {} with default credentials, trying to connect with managed credentials", dataCenter.getMetadata().getName(), e);
                cqlConnectionManager.add(dataCenter, strapkopCredentials);
                logger.info("successfully connected to {} with managed credentials", dataCenter.getMetadata().getName());
            }
            
            dataCenter.getStatus().setCredentialsStatus(CredentialsStatus.MANAGED);
            dataCenter.getStatus().setCqlStatus(CqlStatus.ESTABLISHED);
            dataCenter.getStatus().setCqlErrorMessage("");
            updateDataCenter(dataCenter);
            
//            patchDataCenterStatus(dataCenter, ImmutableMap.of(
//                    "/status/credentialsStatus", new JsonPrimitive(CredentialsStatus.MANAGED.toString()),
//                    "/status/cqlStatus", new JsonPrimitive(CqlStatus.ESTABLISHED.toString())
//                    "/status/cqlErrorMessage", null));
    
            logger.info("reconciled credentials for {}", dataCenter.getMetadata().getName());
        }
        catch (DriverException e) {
            dataCenter.getStatus().setCredentialsStatus(CredentialsStatus.UNKNOWN);
            dataCenter.getStatus().setCqlStatus(CqlStatus.ERRORED);
            dataCenter.getStatus().setCqlErrorMessage(e.getMessage());
            updateDataCenter(dataCenter);
//            patchDataCenterStatus(dataCenter, ImmutableMap.of(
//                    "/status/credentialsStatus", new JsonPrimitive(CredentialsStatus.UNKNOWN.toString()),
//                    "/status/cqlStatus", new JsonPrimitive(CqlStatus.ERRORED.toString()),
//                    "/status/cqlErrorMessage", new JsonPrimitive(e.getMessage())));
        }
    }
    
    private void updateDataCenter(DataCenter dataCenter) throws ApiException {
        // HUm... currently this reconilier is called from DataCenterReconcilier so we can't update the status now because it will be updated later anyway
//        customObjectsApi.patchNamespacedCustomObject("stable.strapdata.com", "v1",
//                dataCenter.getMetadata().getNamespace(), "elassandra-datacenters", dataCenter.getMetadata().getName(),
//                dataCenter);
    }
    
    private void createRole(CqlCredentials credentials, Session session) throws StrapkopException {
        if (credentials.getUsername().matches(".*[\"\';].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra username %s", credentials.getUsername()));
        }
        if (credentials.getPassword().matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", credentials.getUsername()));
        }
        session.execute(String.format("CREATE ROLE %s with SUPERUSER = true AND LOGIN = true and PASSWORD = '%s'", credentials.getUsername(), credentials.getPassword()));
    }
    
    // Patch is not working for obscure reason
//    private void patchDataCenterStatus(final DataCenter dataCenter, Map<String, JsonElement> patch) throws ApiException {
//        final ArrayList<JsonObject> patchBody = new ArrayList<>();
//
//        patch.forEach((path, value) -> {
//            final JsonObject jsonPatch = new JsonObject();
//            jsonPatch.addProperty("op", "replace");
//            jsonPatch.addProperty("path", path);
//            jsonPatch.add("value", value);
//            patchBody.add(jsonPatch);
//        });
//
//        customObjectsApi.patchNamespacedCustomObject("stable.strapdata.com", "v1",
//                dataCenter.getMetadata().getNamespace(), "elassandra-datacenters", dataCenter.getMetadata().getName(),
//                patchBody
//        );
//    }
//
    private CqlCredentials loadCredentialsFromSecret(final DataCenter dataCenter, final String secretName) throws ApiException, StrapkopException {
        final V1Secret secret = coreApi.readNamespacedSecret(secretName,
                dataCenter.getMetadata().getNamespace(),
                null,
                null,
                null);
        
        final byte[] usernameBytes = secret.getData().get("username");
        final byte[] passwordBytes = secret.getData().get("password");
        
        if (usernameBytes == null || passwordBytes == null) {
            throw new StrapkopException(String.format("secret %s does not contain the correct username and password fields", secretName));
        }
        
        return new CqlCredentials()
                .setUsername(new String(usernameBytes))
                .setPassword(new String(passwordBytes));
    }
}
