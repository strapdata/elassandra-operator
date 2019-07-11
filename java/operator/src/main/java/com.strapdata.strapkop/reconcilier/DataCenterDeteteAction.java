package com.strapdata.strapkop.reconcilier;

import com.google.gson.JsonSyntaxException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorMetadata;
import com.strapdata.model.Key;
import io.kubernetes.client.ApiException;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Prototype
public class DataCenterDeteteAction {
    private static final Logger logger = LoggerFactory.getLogger(DataCenterDeteteAction.class);
    
    private final K8sResourceUtils k8sResourceUtils;
    private final Key dataCenterKey;
    
    public DataCenterDeteteAction(K8sResourceUtils k8sResourceUtils, @Parameter("dataCenterKey") Key dataCenterKey) {
        this.k8sResourceUtils = k8sResourceUtils;
        this.dataCenterKey = dataCenterKey;
    }
    
    public void deleteDataCenter() throws Exception {
        logger.info("Deleting DataCenter {}.", dataCenterKey.name);
        
        final String labelSelector = OperatorMetadata.toSelector(OperatorMetadata.datacenter(dataCenterKey.name));
        
        // delete persistent volumes & persistent volume claims
        // TODO: this is disabled for now for safety. Perhaps add a flag or something to control this.
//        k8sResourceUtils.listNamespacedPods(dataCenterKey.namespace, null, labelSelector).forEach(pod -> {
//            try (@SuppressWarnings("unused") final MDC.MDCCloseable _podMDC = putNamespacedName("Pod", pod.getMetadata())) {
//                try {
//                    k8sResourceUtils.deletePersistentVolumeAndPersistentVolumeClaim(pod);
//                    logger.debug("Deleted Pod Persistent Volume & Claim.");
//
//                } catch (final ApiException e) {
//                    logger.error("Failed to delete Pod Persistent Volume and/or Claim.", e);
//                }
//            }
//        });
        
        // delete StatefulSets
        k8sResourceUtils.listNamespacedStatefulSets(dataCenterKey.namespace, null, labelSelector).forEach(statefulSet -> {
            try {
                k8sResourceUtils.deleteStatefulSet(statefulSet);
                logger.debug("Deleted StatefulSet.");
                
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting StatefulSet. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                
            } catch (final ApiException e) {
                logger.error("Failed to delete StatefulSet.", e);
            }
        });
        
        // delete ConfigMaps
        k8sResourceUtils.listNamespacedConfigMaps(dataCenterKey.namespace, null, labelSelector).forEach(configMap -> {
            try {
                k8sResourceUtils.deleteConfigMap(configMap);
                logger.debug("Deleted ConfigMap.");
                
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting ConfigMap. Iignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                
            } catch (final ApiException e) {
                logger.error("Failed to delete ConfigMap.", e);
            }
        });
        
        // delete Services
        k8sResourceUtils.listNamespacedServices(dataCenterKey.namespace, null, labelSelector).forEach(service -> {
            try {
                k8sResourceUtils.deleteService(service);
                logger.debug("Deleted Service.");
                
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                
            } catch (final ApiException e) {
                logger.error("Failed to delete Service.", e);
            }
        });
        
        logger.info("Deleted DataCenter.");
    }
}
