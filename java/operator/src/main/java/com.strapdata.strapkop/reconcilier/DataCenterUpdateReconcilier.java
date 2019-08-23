package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.*;
import com.strapdata.strapkop.ReaperClient;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.cql.CqlCredentialsManager;
import com.strapdata.strapkop.cql.KeyspacesManager;
import com.strapdata.strapkop.exception.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Secret;
import io.micronaut.context.ApplicationContext;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class DataCenterUpdateReconcilier extends Reconcilier<Key> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterUpdateReconcilier.class);
    
    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;
    private final CqlCredentialsManager cqlCredentialsManager;
    private final KeyspacesManager keyspacesManager;
    private final CqlConnectionManager cqlConnectionManager;
    private final CoreV1Api coreApi;
    
    public DataCenterUpdateReconcilier(final ApplicationContext context, K8sResourceUtils k8sResourceUtils, CqlCredentialsManager cqlCredentialsManager, KeyspacesManager keyspacesManager, CqlConnectionManager cqlConnectionManager, CoreV1Api coreApi) {
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
        this.cqlCredentialsManager = cqlCredentialsManager;
        this.keyspacesManager = keyspacesManager;
        this.cqlConnectionManager = cqlConnectionManager;
        this.coreApi = coreApi;
    }
    
    @Override
    void reconcile(final Key key) throws ApiException {
        
        DataCenter dc = null;
        try {
            // this is a "read-before-write" to ensure we are processing the latest resource version (otherwise, status update will failed with a 409 conflict)
            // TODO: maybe we can use the datacenter cache in a smart way.
            //      ...something like : when we update the dc status, we notify the cache to invalidate the data until we receive an update
            dc = k8sResourceUtils.readDatacenter(key);
            
            // abort if there is a task currently executing
            if (dc.getStatus() != null && Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.EXECUTING_TASK)) {
                logger.debug("do not reconcile datacenter as a task is already being executed ({})", dc.getStatus().getCurrentTask());
                return ;
            }
            
            // call the main reconciliation
            logger.debug("processing a dc reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());
            context.createBean(DataCenterUpdateAction.class, dc).reconcileDataCenter();
            
            // reconcile cql connection
            cqlConnectionManager.reconcileConnection(dc, cqlCredentialsManager);
            
            // reconcile credentials
            cqlCredentialsManager.reconcileCredentials(dc);
            
            // reconcile keyspaces
            keyspacesManager.reconcileKeyspaces(dc);
            
            // reconcile reaper cluster registration
            reconcileReaperRegistration(dc);
            
            // update status can only happen at the end
            k8sResourceUtils.updateDataCenterStatus(dc);
        } catch (Exception e) {
            logger.error("an error occurred while processing DataCenter update reconciliation for {}", key.getName(), e);
            if (dc != null) {
                if (dc.getStatus() == null) {
                    dc.setStatus(new DataCenterStatus());
                }
                dc.getStatus().setPhase(DataCenterPhase.ERROR);
                dc.getStatus().setLastErrorMessage(e.getMessage());
                k8sResourceUtils.updateDataCenterStatus(dc);
            }
        }
    }
    
    
    /**
     * As soon as reaper_db keyspace is created, this function try to ping the reaper api and, if success, register the datacenter.
     * THe registration is done only once. If the datacenter is unregistered by the user, it will not register it again automatically.
     */
    private void reconcileReaperRegistration(DataCenter dc) throws StrapkopException, ApiException {
    
        if (dc.getStatus().getReaperStatus().equals(ReaperStatus.KEYSPACE_INITIALIZED)) {
            
            final String reaperAdminPassword = loadReaperAdminPassword(dc);
            
            try (ReaperClient reaperClient = new ReaperClient(dc, "admin", reaperAdminPassword)) {
        
                if (!reaperClient.ping().blockingGet()) {
                    logger.info("reaper is not ready before registration, waiting");
                }
                else {
                    reaperClient.registerCluster()
                            .observeOn(Schedulers.io())
                            .subscribeOn(Schedulers.io())
                            .blockingGet();
                    dc.getStatus().setReaperStatus(ReaperStatus.REGISTERED);
                    logger.info("registered dc={} in cassandra-reaper", dc.getMetadata().getName());
                }
            }
            catch (Exception e) {
                dc.getStatus().setLastErrorMessage(e.getMessage());
                logger.error("error while registering dc={} in cassandra-reaper", dc.getMetadata().getName(), e);
            }
        }
    }
    
    // TODO: cache cluster secret to avoid loading secret again and again
    private String loadReaperAdminPassword(DataCenter dc) throws ApiException, StrapkopException {
        final String secretName = OperatorNames.clusterSecret(dc);
        final V1Secret secret = coreApi.readNamespacedSecret(secretName,
                dc.getMetadata().getNamespace(),
                null,
                null,
                null);
        final byte[] password = secret.getData().get("reaper.admin_password");
        if (password == null) {
            throw new StrapkopException(String.format("secret %s does not contain reaper.admin_password", secretName));
        }
        return new String(password);
    
    }
}
