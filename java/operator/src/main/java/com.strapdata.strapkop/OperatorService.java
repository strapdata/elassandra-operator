package com.strapdata.strapkop;

import com.google.common.collect.Sets;
import com.instaclustr.model.Key;
import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.instaclustr.model.sidecar.NodeStatus;
import com.strapdata.strapkop.controllers.DataCenterControllerFactory;
import com.strapdata.strapkop.controllers.DataCenterDeletionController;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.watch.DataCenterWatchService;
import com.strapdata.strapkop.watch.StatefulSetWatchService;
import com.strapdata.strapkop.watch.WatchEvent;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Listen for datacenter, statefulset and Cassandra node status events to trigger DC deletion or reconciliation
 */
@Context
public class OperatorService {
    private static final Logger logger = LoggerFactory.getLogger(OperatorService.class);
    
    private static final Set<NodeStatus> RECONCILE_OPERATION_MODES = Sets.immutableEnumSet(
        // Reconcile when nodes switch to NORMAL. There may be pending scale operations that were
        // waiting for a healthy cluster.
        NodeStatus.NORMAL,

        // Reconcile when nodes have finished decommissioning. This will resume the StatefulSet
        // reconciliation.
        NodeStatus.DECOMMISSIONED
    );

    private final DataCenterWatchService dataCenterWatchService;
    private final StatefulSetWatchService statefulSetWatchService;
    private final CassandraHealthCheckService cassandraHealthCheckService;
    private final DataCenterControllerFactory dataCenterControllerFactory;

    public OperatorService(DataCenterWatchService dataCenterWatchService,
                           StatefulSetWatchService statefulSetWatchService,
                           BackupControllerService backupControllerService,
                           CassandraHealthCheckService cassandraHealthCheckService,
                           ApplicationContext beanContext, DataCenterControllerFactory dataCenterControllerFactory) {
        logger.info("Initializing OperatorService");
        this.dataCenterControllerFactory = dataCenterControllerFactory;
        this.dataCenterWatchService = dataCenterWatchService;
        this.dataCenterWatchService.getSubject()
            .subscribe(event -> {
                logger.debug("Received DataCenterWatchEvent {}.", event);
                if (event instanceof WatchEvent.IDeleted) {
                    try {
                        dataCenterControllerFactory.createDeletionController(new Key<>(event.t.getMetadata())).deleteDataCenter();
                    } catch (final Exception e) {
                        logger.warn("Failed to delete Data Center.", e);
                    }
                } else {
                    try {
                        dataCenterControllerFactory.createReconciliationController(event.t).reconcileDataCenter();
                    } catch (final Exception e) {
                        logger.warn("Failed to reconcile Data Center.", e);
                    }
                }

            });

        this.statefulSetWatchService = statefulSetWatchService;
        statefulSetWatchService.getSubject()
            .subscribe(event -> {
                logger.debug("Received StatefulSetWatchEvent {}.", event);
                if (event instanceof WatchEvent.Modified) {
                    // Trigger a dc reconciliation event if changes to the stateful set has finished.
                    if (event.t.getStatus().getReplicas().equals(event.t.getStatus().getReadyReplicas()) && event.t.getStatus().getCurrentReplicas().equals(event.t.getStatus().getReplicas())) {
                        String datacenterName = event.t.getMetadata().getLabels().get(OperatorLabels.DATACENTER);
                        if (datacenterName != null) {
                            DataCenter dataCenter = dataCenterWatchService.get(new Key<>(datacenterName, event.t.getMetadata().getNamespace()));
                            if (dataCenter != null) {
                                dataCenterControllerFactory.createReconciliationController(dataCenter).reconcileDataCenter();
                            }
                        }
                    }
                }
            });


        this.cassandraHealthCheckService = cassandraHealthCheckService;
        this.cassandraHealthCheckService.getSubject()
            .subscribe(event -> {
                logger.debug("Received CassandraNodeOperationModeChangedEvent {}.", event);
                if (!RECONCILE_OPERATION_MODES.contains(event.currentMode))
                    return;
                try {
                    dataCenterControllerFactory.createReconciliationController(dataCenterWatchService.get(event.dataCenterKey)).reconcileDataCenter();
                } catch (final Exception e) {
                    logger.warn("Failed to reconcile Data Center.", e);
                }
            });
    }
}
