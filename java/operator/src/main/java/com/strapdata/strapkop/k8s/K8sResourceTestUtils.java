package com.strapdata.strapkop.k8s;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
public class K8sResourceTestUtils extends K8sResourceUtils{
    private static final Logger logger = LoggerFactory.getLogger(K8sResourceTestUtils.class);

    public boolean podExists(final String namespace, final String podName) throws ApiException {
        V1Pod pods = coreApi.readNamespacedPod(podName, namespace, null, null, null);
        return (pods != null);
    }

    /**
     * Return true if there are pods matching the label/value selector
     * @param namespace
     * @param label
     * @param value
     * @return
     * @throws ApiException
     */
    public boolean podExists(final String namespace, final String label, final String value) throws ApiException {
        final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(label, value));
        V1PodList pods = coreApi.listNamespacedPod(namespace, null, null, null,
                null, labelSelector, null, null, null, null);
        return (pods != null && !pods.getItems().isEmpty());
    }

    public boolean deletePod(final String namespace, final String podname) throws ApiException {
        V1DeleteOptions v1DeleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        V1Status status = coreApi.deleteNamespacedPod(podname, namespace, v1DeleteOptions, null, null, null, null, "Foreground");
        return status.getCode() == 200;
    }
}
