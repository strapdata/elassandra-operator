package com.strapdata.strapkop.k8s;

import com.google.common.base.Strings;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskList;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskSpec;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.apis.ExtensionsV1beta1Api;
import io.kubernetes.client.models.*;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
}
