package com.strapdata.strapkop.reconcilier;

import com.google.gson.JsonSyntaxException;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cache.SidecarConnectionCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlSessionSupplier;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
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
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final SidecarConnectionCache sidecarConnectionCache;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    
    public DataCenterDeleteAction(K8sResourceUtils k8sResourceUtils,
                                  CoreV1Api coreV1Api,
                                  AppsV1Api appsV1Api,
                                  ElassandraNodeStatusCache elassandraNodeStatusCache,
                                  SidecarConnectionCache sidecarConnectionCache,
                                  CqlKeyspaceManager cqlKeyspaceManager,
                                  @Parameter("dataCenter") DataCenter dataCenter) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.coreV1Api = coreV1Api;
        this.dataCenter = dataCenter;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }
    
    Completable deleteDataCenter(final CqlSessionSupplier cqlSessionSupplier) throws Exception {
        // remove the datacenter from replication maps of managed keyspaces
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                cqlKeyspaceManager.removeDatacenter(dataCenter, cqlSessionSupplier);

                final String labelSelector = OperatorLabels.toSelector(OperatorLabels.datacenter(dataCenter));

                // cleanup local caches
                elassandraNodeStatusCache.purgeDataCenter(dataCenter);
                sidecarConnectionCache.purgeDataCenter(dataCenter);

                // delete tasks

                // delete StatefulSets
                k8sResourceUtils.listNamespacedStatefulSets(dataCenter.getMetadata().getNamespace(), null, labelSelector).forEach(statefulSet -> {
                    try {
                        k8sResourceUtils.deleteStatefulSet(statefulSet);
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
                        logger.error("Failed to delete ConfigMap.", e);
                    }
                });

                try {
                    // delete secrets
                    coreV1Api.deleteCollectionNamespacedSecret(dataCenter.getMetadata().getNamespace(), false,
                            null, null, null, labelSelector, null, null, null, null);
                    logger.debug("Deleted Secrets namespace={}", dataCenter.getMetadata().getNamespace());
                } catch (final JsonSyntaxException e) {
                    logger.debug("Caught JSON exception while deleting Secrets. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                } catch (final ApiException e) {
                    logger.error("Failed to delete Secrets.", e);
                }

                // delete Services
                k8sResourceUtils.deleteService(dataCenter.getMetadata().getNamespace(), null, labelSelector);

                // delete persistent volume claims
                switch (dataCenter.getSpec().getDecommissionPolicy()) {
                    case KEEP_PVC:
                        break;
                    case BACKUP_AND_DELETE_PVC:
                        // TODO: backup
                    case DELETE_PVC:
                        k8sResourceUtils.listNamespacedPods(dataCenter.getMetadata().getNamespace(), null, labelSelector).forEach(pod -> {
                            try {
                                k8sResourceUtils.deletePersistentVolumeClaim(pod);
                            } catch (final JsonSyntaxException e) {
                                logger.debug("Caught JSON exception while deleting PVC. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                            } catch (final ApiException e) {
                                logger.error("Failed to delete PVC for pod={}" + pod.getMetadata().getName(), e);
                            }
                        });
                        break;
                }

                logger.info("Deleted DataCenter namespace={} name={}", dataCenter.getMetadata().getNamespace(), dataCenter.getMetadata().getName());
            }
        });
    }
}
