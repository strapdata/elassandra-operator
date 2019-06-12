package com.strapdata.strapkop;

import com.google.common.collect.Sets;
import com.instaclustr.model.Key;
import com.instaclustr.model.sidecar.NodeStatus;
import com.strapdata.strapkop.controllers.DataCenterDeletionController;
import com.strapdata.strapkop.controllers.DataCenterReconciliationController;
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

    public OperatorService(DataCenterWatchService dataCenterWatchService,
                           StatefulSetWatchService statefulSetWatchService,
                           BackupControllerService backupControllerService,
                           CassandraHealthCheckService cassandraHealthCheckService,
                           ApplicationContext beanContext) {
        logger.info("Initializing OperatorService");
        this.dataCenterWatchService = dataCenterWatchService;
        this.dataCenterWatchService.getSubject()
            .subscribe(event -> {
                logger.debug("Received DataCenterWatchEvent {}.", event);
                if (event instanceof WatchEvent.IDeleted) {
                    try {
                        beanContext.getBean(DataCenterDeletionController.class).deleteDataCenter(new Key<>(event.t.getMetadata()));
                    } catch (final Exception e) {
                        logger.warn("Failed to delete Data Center.", e);
                    }
                } else {
                    try {
                        beanContext.getBean(DataCenterReconciliationController.class).reconcileDataCenter(event.t);
                    } catch (final Exception e) {
                        logger.warn("Failed to reconcile Data Center.", e);
                    }
                }

            });

        this.statefulSetWatchService = statefulSetWatchService;
        statefulSetWatchService.getSubject()
            .subscribe(event -> {
                logger.debug("Received StatefulSetWatchEvent {}.", event);
                if (event instanceof WatchEvent.Added) {
                    return;
                }

                // Trigger a dc reconciliation event if changes to the stateful set has finished.
                if (event.t.getStatus().getReplicas().equals(event.t.getStatus().getReadyReplicas()) && event.t.getStatus().getCurrentReplicas().equals(event.t.getStatus().getReplicas())) {
                    String datacenterName = event.t.getMetadata().getLabels().get(OperatorLabels.DATACENTER);
                    if (datacenterName != null) {
                        beanContext.getBean(DataCenterReconciliationController.class)
                                .reconcileDataCenter(dataCenterWatchService.get(new Key<>(event.t.getMetadata())));
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
                    beanContext.getBean(DataCenterReconciliationController.class).reconcileDataCenter(dataCenterWatchService.get(event.dataCenterKey));
                } catch (final Exception e) {
                    logger.warn("Failed to reconcile Data Center.", e);
                }
            });
    }
}
