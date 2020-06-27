/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.reconcilier;

import com.google.gson.JsonSyntaxException;
import com.strapdata.strapkop.utils.BackupScheduler;
import com.strapdata.strapkop.cache.*;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionSupplier;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Completable;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: we should probably use kubernetes GC to manage deletion : https://kubernetes.io/docs/concepts/workloads/controllers/garbage-collection/
 */
@Prototype
public class DataCenterDeleteAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterDeleteAction.class);

    private final K8sResourceUtils k8sResourceUtils;
    private final CoreV1Api coreV1Api;
    private final DataCenter dataCenter;
    private final DataCenterCache dataCenterCache;
    private final DataCenterStatusCache dataCenterStatusCache;
    private final HttpConnectionCache sidecarConnectionCache;
    private final JMXConnectorCache jmxConnectorCache;
    private final StatefulsetCache statefulsetCache;
    private final ServiceAccountCache serviceAccountCache;
    private final PodCache podCache;

    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final CqlRoleManager cqlRoleManager;
    private final BackupScheduler backupScheduler;
    private final MeterRegistry meterRegistry;

    public DataCenterDeleteAction(K8sResourceUtils k8sResourceUtils,
                                  CoreV1Api coreV1Api,
                                  AppsV1Api appsV1Api,
                                  final DataCenterCache dataCenterCache,
                                  final DataCenterStatusCache dataCenterStatusCache,
                                  final HttpConnectionCache sidecarConnectionCache,
                                  final JMXConnectorCache jmxConnectorCache,
                                  final StatefulsetCache statefulsetCache,
                                  final ServiceAccountCache serviceAccountCache,
                                  final PodCache podCache,
                                  CqlKeyspaceManager cqlKeyspaceManager,
                                  CqlRoleManager cqlRoleManager,
                                  @Parameter("dataCenter") DataCenter dataCenter,
                                  BackupScheduler backupScheduler,
                                  final MeterRegistry meterRegistry) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.coreV1Api = coreV1Api;
        this.dataCenter = dataCenter;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterStatusCache = dataCenterStatusCache;
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.serviceAccountCache = serviceAccountCache;
        this.statefulsetCache = statefulsetCache;
        this.jmxConnectorCache = jmxConnectorCache;
        this.podCache = podCache;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.cqlRoleManager = cqlRoleManager;
        this.backupScheduler = backupScheduler;
        this.meterRegistry = meterRegistry;
    }

    Completable deleteDataCenter(final CqlSessionSupplier cqlSessionSupplier) throws Exception {
        // remove the datacenter from replication maps of managed keyspaces
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {

                backupScheduler.cancelBackups(new Key(dataCenter.getMetadata()));

                // cleanup local caches
                Key key = new Key(dataCenter.getMetadata().getName(), dataCenter.getMetadata().getNamespace());
                dataCenterCache.remove(key);
                dataCenterStatusCache.remove(key);
                statefulsetCache.remove(key);

                sidecarConnectionCache.purgeDataCenter(dataCenter);
                jmxConnectorCache.purgeDataCenter(dataCenter);
                podCache.purgeDataCenter(dataCenter);
                serviceAccountCache.purgeServiceAccount(dataCenter);

                cqlRoleManager.remove(dataCenter);
                cqlKeyspaceManager.remove(dataCenter);

                final String labelSelector = OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter));
                // delete StatefulSets
                k8sResourceUtils.listNamespacedStatefulSets(dataCenter.getMetadata().getNamespace(), null, labelSelector).forEach(statefulSet -> {
                    try {
                        k8sResourceUtils.deleteStatefulSet(statefulSet).blockingGet();
                        logger.debug("Deleted StatefulSet namespace={} name={}", dataCenter.getMetadata().getNamespace(), statefulSet.getMetadata().getName());
                    } catch (final JsonSyntaxException e) {
                        logger.debug("Caught JSON exception while deleting StatefulSet. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                    } catch (final ApiException e) {
                        logger.error("Failed to delete StatefulSet.", e);
                    }
                });

                // delete ConfigMaps
                k8sResourceUtils.listNamespacedConfigMaps(dataCenter.getMetadata().getNamespace(), null, labelSelector).forEach(configMap -> {
                    try {
                        k8sResourceUtils.deleteConfigMap(configMap);
                        logger.debug("Deleted ConfigMap namespace={} name={}", dataCenter.getMetadata().getNamespace(), configMap.getMetadata().getName());
                    } catch (final JsonSyntaxException e) {
                        logger.debug("Caught JSON exception while deleting ConfigMap. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                    } catch (final ApiException e) {
                        if (e.getCode() != 404)
                            logger.error("Failed to delete ConfigMap.", e);
                    }
                });

                try {
                    // delete secrets
                    coreV1Api.deleteCollectionNamespacedSecret(dataCenter.getMetadata().getNamespace(), "false",
                            null, null, null, null, null, labelSelector, null,
                            null, null, null, null, null, null);
                    logger.debug("Deleted Secrets namespace={}", dataCenter.getMetadata().getNamespace());
                } catch (final JsonSyntaxException e) {
                    logger.debug("Caught JSON exception while deleting Secrets. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                } catch (final ApiException e) {
                    logger.error("Failed to delete Secrets.", e);
                }

                // delete Services
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, labelSelector)
                        .onErrorComplete()
                        .blockingGet();

                // delete tasks
                k8sResourceUtils.deleteTasks(dataCenter.getMetadata().getNamespace(), null).blockingGet();

                // delete persistent volume claims
                switch (dataCenter.getSpec().getDecommissionPolicy()) {
                    case KEEP_PVC:
                        break;
                    case SNAPSHOT_AND_DELETE_PVC:
                        // TODO: backup
                    case DELETE_PVC:
                        k8sResourceUtils.listNamespacedPodsPersitentVolumeClaims(dataCenter.getMetadata().getNamespace(), null, labelSelector).forEach(volumeClaim -> {
                            try {
                                k8sResourceUtils.deletePersistentVolumeClaim(volumeClaim).blockingGet();
                                logger.debug("PVC={} deleted", volumeClaim.getMetadata().getName());
                            } catch (final JsonSyntaxException e) {
                                logger.debug("Caught JSON exception while deleting PVC. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                            } catch (final ApiException e) {
                                logger.error("Failed to delete PVC for volumeClaim={}" + volumeClaim.getMetadata().getName(), e);
                            }
                        });
                        break;
                }
                logger.info("Deleted dataCenter={}", dataCenter.id());
            }
        });
    }
}
