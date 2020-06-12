package com.strapdata.strapkop.k8s;

import com.google.common.base.Strings;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterList;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskList;
import com.strapdata.strapkop.model.k8s.task.TaskSpec;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.ApiResponse;
import io.kubernetes.client.openapi.apis.*;
import io.kubernetes.client.openapi.models.*;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Singleton
public class K8sResourceUtils {
    private static final Logger logger = LoggerFactory.getLogger(K8sResourceUtils.class);

    @Inject
    protected CoreV1Api coreApi;

    @Inject
    protected AppsV1Api appsApi;

    @Inject
    protected CustomObjectsApi customObjectsApi;

    @Inject
    protected ExtensionsV1beta1Api extensionsV1beta1Api;

    @Inject
    protected PolicyV1beta1Api policyV1beta1Api;

    @Inject
    DataCenterStatusCache dataCenterStatusCache;

    @FunctionalInterface
    public interface ApiCallable {
        void call() throws ApiException;
    }


    public static <T> Single<Iterable<T>> listNamespacedResources(String namespace, K8sSupplier<Iterable<T>> lister) throws ApiException {
        return Single.fromCallable(new Callable<Iterable<T>>() {
            @Override
            public Iterable<T> call() throws Exception {
                try {
                    return lister.get();
                } catch (final ApiException e) {
                    logger.error("list error code={} namespace={}", e.getCode(), namespace);
                    throw e;
                }
            }
        });
    }

    public static <T> Single<T> createNamespacedResource(String namespace, T t, K8sSupplier<T> create) throws ApiException {
        return Single.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    return create.get();
                } catch (final ApiException e) {
                    logger.error("create error code={} namespace={} object={}", e.getCode(), namespace, t);
                    throw e;
                }
            }
        });
    }

    public static <T> Single<T> createOrReplaceResource(String namespace, T t, K8sSupplier<T> create, K8sSupplier<T> replace) throws ApiException {
        return Single.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    return create.get();
                } catch (final ApiException e) {
                    if (e.getCode() != 409) {
                        logger.error("create error code={} namespace={} object={}", e.getCode(), namespace, t);
                        throw e;
                    }
                    try {
                        return replace.get();
                    } catch (final ApiException e2) {
                        logger.error("replace error code={} namespace={} object={}", e2.getCode(), namespace, t);
                        throw e2;
                    }
                }
            }
        });
    }

    public static <T> Single<T> readOrCreateResource(String namespace, T t, K8sSupplier<T> read, K8sSupplier<T> create) throws ApiException {
        return Single.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    return read.get();
                } catch (final ApiException e) {
                    if (e.getCode() != 404) {
                        logger.error("read error code={} namespace={} object={}", e.getCode(), namespace, t);
                        throw e;
                    }
                    try {
                        return create.get();
                    } catch (final ApiException e2) {
                        logger.error("create error code={} namespace={} object={}", e2.getCode(), namespace, t);
                        throw e2;
                    }
                }
            }
        });
    }

    public static  <T> Completable deleteNamespacedResource(String namespace, T t, K8sSupplier<T> delete) {
        return Completable.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    return delete.get();
                } catch (final ApiException e) {
                    logger.error("delete error code={} namespace={} object={}", e.getCode(), namespace, t);
                    throw e;
                }
            }
        });
    }

    public static <T> Single<T> readOrCreateResource(final Callable<T> getResourceCallable, final Callable<T> createResourceCallable) throws ApiException {
        return Single.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    logger.trace("Attempting to get resource.");
                    return getResourceCallable.call();
                } catch (final ApiException e) {
                    if (e.getCode() != 404)
                        throw e;

                    logger.trace("Resource does not exist, create it.");
                    return createResourceCallable.call();
                }
            }
        });
    }

    public static Completable deleteResource(final Callable<V1Status> deleteResourceRunnable) {
        return Completable.fromCallable(deleteResourceRunnable);
    }

    public Single<V1Service> createOrReplaceNamespacedService(final V1Service service) throws ApiException, IOException {
        final String namespace = service.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, service,
                () -> coreApi.createNamespacedService(namespace, service, null, null, null),
                () -> {
            /*  CANNOT UPDATE SERVICE !
                    // read resourceVersion to update service, see https://github.com/kubernetes/kubernetes/issues/70674
                    V1Service s = debuggableCore.readNamespacedService(service.getMetadata().getName(), namespace, null, false, false);
                    V1ObjectMeta metadata = service.getMetadata();
                    metadata.setResourceVersion(s.getMetadata().getResourceVersion());
                    service.setMetadata(metadata);
                    return debuggableCore.replaceNamespacedService(service.getMetadata().getName(), service.getMetadata().getNamespace(), service, null, null);
             */
                    return service;
                });
    }

    public Single<V1Service> createNamespacedService(final V1Service service) throws ApiException {
        final String namespace = service.getMetadata().getNamespace();
        return createNamespacedResource(namespace, service,
                () -> coreApi.createNamespacedService(namespace, service, null, null, null));
    }

    public Single<ExtensionsV1beta1Ingress> createOrReplaceNamespacedIngress(final ExtensionsV1beta1Ingress ingress) throws ApiException {
        final String namespace = ingress.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, ingress,
                () -> extensionsV1beta1Api.createNamespacedIngress(namespace, ingress, null, null, null),
                () -> {
                    // CANNOT UPDATE ingres (like service)
                    // extensionsV1beta1Api.replaceNamespacedIngress(ingress.getMetadata().getName(), ingress.getMetadata().getNamespace(), ingress, null, null)
                    return ingress;
                });
    }

    public Single<V1ConfigMap> createOrReplaceNamespacedConfigMap(final V1ConfigMap configMap) throws ApiException {
        final String namespace = configMap.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, configMap,
                () -> coreApi.createNamespacedConfigMap(namespace, configMap, null, null, null),
                () -> coreApi.replaceNamespacedConfigMap(configMap.getMetadata().getName(), namespace, configMap, null, null, null));
    }

    public Single<V1ConfigMap> readNamespacedConfigMap(final String namespace, final String name) {
        return Single.fromCallable(new Callable<V1ConfigMap>() {
            @Override
            public V1ConfigMap call() throws Exception {
                try {
                    V1ConfigMap configMap = coreApi.readNamespacedConfigMap(name, namespace, null, null, null);
                    logger.debug("read namespaced ConfigMap={}", configMap.getMetadata().getName());
                    return configMap;
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("ConfigMap namespace={} name={} not found", namespace, name);
                        throw new NoSuchElementException("configmap="+name+"/"+namespace+" not found");
                    }
                    throw e;
                }
            }
        });
    }

    public Single<V1Deployment> createOrReplaceNamespacedDeployment(final V1Deployment deployment) throws ApiException {
        final String namespace = deployment.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, deployment,
                () -> appsApi.createNamespacedDeployment(namespace, deployment, null, null, null),
                () -> appsApi.replaceNamespacedDeployment(deployment.getMetadata().getName(), namespace, deployment, null, null, null));
    }

    public Single<V1Deployment> updateNamespacedDeployment(final V1Deployment deployment) throws ApiException {
        return Single.fromCallable(new Callable<V1Deployment>() {
            @Override
            public V1Deployment call() throws Exception {
                return appsApi.replaceNamespacedDeployment(deployment.getMetadata().getName(), deployment.getMetadata().getNamespace(), deployment, null, null, null);
            }
        });
    }

    public Single<V1StatefulSet> createOrReplaceNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, statefulset,
                () -> appsApi.createNamespacedStatefulSet(namespace, statefulset, null, null, null),
                () -> appsApi.replaceNamespacedStatefulSet(statefulset.getMetadata().getName(), namespace, statefulset, null, null, null));
    }

    public Single<V1StatefulSet> createNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return Single.fromCallable(
                () -> {
                    try {
                        V1StatefulSet statefulSet2 = appsApi.createNamespacedStatefulSet(namespace, statefulset, null, null, null);
                        logger.debug("Created namespaced statefulset={} in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                        return statefulSet2;
                    } catch (ApiException e) {
                        logger.warn("Created namespaced statefulset={} in namespace={} error: {}",
                                statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace(), e.getMessage());
                        throw e;
                    }
                });
    }

    public Single<V1Secret> createNamespacedSecret(final V1Secret secret) throws ApiException {
        final String namespace = secret.getMetadata().getNamespace();
        return Single.fromCallable(
                () -> {
                    try {
                        V1Secret secret2 = coreApi.createNamespacedSecret(namespace, secret, null, null, null);
                        logger.debug("Created namespaced secret={} in namespace={}", secret2.getMetadata().getName(), secret2.getMetadata().getNamespace());
                        return secret2;
                    } catch(ApiException e) {
                        logger.warn("Created namespaced secret={} in namespace={} error: {}",
                                secret.getMetadata().getName(), secret.getMetadata().getNamespace(), e.getMessage());
                        throw e;
                    }
                });
    }

    public Single<V1Secret> createOrReplaceNamespacedSecret(final V1Secret secret) throws ApiException {
        final String namespace = secret.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, secret,
                () -> coreApi.createNamespacedSecret(namespace, secret, null, null, null),
                () -> coreApi.replaceNamespacedSecret(secret.getMetadata().getName(), namespace, secret, null, null, null));
    }

    public Single<V1StatefulSet> replaceNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return Single.fromCallable(() -> {
                    try {
                        V1StatefulSet statefulSet2 = appsApi.replaceNamespacedStatefulSet(statefulset.getMetadata().getName(), namespace, statefulset, null, null, null);
                        logger.debug("Replaced namespaced statefulset={} in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                        return statefulSet2;
                    } catch (ApiException e) {
                        if (e.getCode() == 404) {
                            throw new NoSuchElementException("statefulset="+statefulset.getMetadata().getName()+"/"+namespace+" not found");
                        }
                        logger.warn("Created namespaced statefulset={} in namespace={} error: {}",
                                statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace(), e.getMessage());
                        throw e;
                    }
                }
        );
    }

    public Single<V1StatefulSet> readNamespacedStatefulSet(final String namespace, final String name) throws ApiException {
        return Single.fromCallable(() -> {
                    try {
                        V1StatefulSet statefulSet2 = appsApi.readNamespacedStatefulSet(name, namespace, null, null, null);
                        logger.debug("Read namespaced Statefulset '{}' in namespace='{}'", name, namespace);
                        return statefulSet2;
                    } catch(ApiException e) {
                        if (e.getCode() == 404) {
                            logger.warn("statefulset namespace={}/{} not found", namespace, name);
                        }
                        throw e;
                    }
                }
        );
    }

    public Single<V1beta1PodDisruptionBudget> createOrReplaceNamespacedPodDisruptionBudget(final V1beta1PodDisruptionBudget v1beta1PodDisruptionBudget) throws ApiException {
        final String namespace = v1beta1PodDisruptionBudget.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, v1beta1PodDisruptionBudget,
                () -> policyV1beta1Api.createNamespacedPodDisruptionBudget(namespace, v1beta1PodDisruptionBudget, null, null, null),
                () -> v1beta1PodDisruptionBudget); // trick to avoid io.kubernetes.client.ApiException: Unprocessable Entity
    }

    public V1ServiceAccount readNamespacedServiceAccount(final String namespace, final String name) throws ApiException {
            try {
                coreApi.getApiClient().setDebugging(true);
                V1ServiceAccount sa = coreApi.readNamespacedServiceAccount(name, namespace, null, null, null);
                logger.debug("read namespaced serviceaccount={}", sa.getMetadata().getName());
                coreApi.getApiClient().setDebugging(false);
                return sa;
            } catch(ApiException e) {
                if (e.getCode() == 404) {
                    logger.warn("serviceaccount namespace={} name={} not found", namespace, name);
                    throw new NoSuchElementException("service="+name+"/"+namespace+" not found");
                }
                throw e;
            }
    }

    public Single<V1Secret> createOrReplaceNamespacedDeployment(final V1Secret secret) throws ApiException {
        final String namespace = secret.getMetadata().getNamespace();
        return createOrReplaceResource(namespace, secret,
                () -> coreApi.createNamespacedSecret(namespace, secret, null, null, null),
                () -> coreApi.replaceNamespacedSecret(secret.getMetadata().getName(), namespace, secret, null, null, null));
    }

    public Single<V1Secret> readOrCreateNamespacedSecret(V1ObjectMeta secretObjectMeta, final Supplier<V1Secret> secretSupplier) throws ApiException {
        return readOrCreateResource(
                () -> {
                    V1Secret secret2 = coreApi.readNamespacedSecret(secretObjectMeta.getName(), secretObjectMeta.getNamespace(), null, null, null);
                    /*
                    logger.warn("Get namespaced secret={} in namespace={} stringData={} data={}",
                            secret2.getMetadata().getName(), secret2.getMetadata().getNamespace(),
                            secret2.getStringData(),
                            secret2.getData().entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> new String(e.getValue()))));
                     */
                    return secret2;
                },
                () -> {
                    V1Secret secret2 = coreApi.createNamespacedSecret(secretObjectMeta.getNamespace(), secretSupplier.get(), null, null, null);
                    logger.debug("Created namespaced secret={}", secret2.getMetadata().getName());
                    return secret2;
                }
        );
    }

    public Single<V1Secret> readNamespacedSecret(final String namespace, final String name) {
        return Single.fromCallable(new Callable<V1Secret>() {
            @Override
            public V1Secret call() throws Exception {
                try {
                    V1Secret secret = coreApi.readNamespacedSecret(name, namespace, null, null, null);
                    logger.debug("read namespaced secret={}", secret.getMetadata().getName());
                    return secret;
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("secret namespace={} name={} not found", namespace, name);
                        throw new NoSuchElementException("secret="+name+"/"+namespace+" not found");
                    }
                    throw e;
                }
            }
        });
    }

    public Single<Optional<V1Secret>> readOptionalNamespacedSecret(final String namespace, final String name) {
        return Single.fromCallable(new Callable<Optional<V1Secret>>() {
            @Override
            public Optional<V1Secret> call() throws Exception {
                try {
                    V1Secret secret = coreApi.readNamespacedSecret(name, namespace, null, null, null);
                    logger.debug("read namespaced secret={}", secret.getMetadata().getName());
                    return Optional.ofNullable(secret);
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("secret namespace={} name={} not found", namespace, name);
                        return Optional.empty();
                    }
                    throw e;
                }
            }
        });
    }

    public Completable deleteService(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) {
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                for (V1Service service : listNamespacedServices(namespace, null, labelSelector)) {
                    try {
                        deleteService(service);
                        logger.debug("Deleted Service namespace={} name={}", service.getMetadata().getNamespace(), service.getMetadata().getName());
                    } catch (final JsonSyntaxException e) {
                        logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                    }
                }
            }
        });
    }

    public V1Status deleteService(final V1Service service) throws ApiException {
        final V1ObjectMeta metadata = service.getMetadata();
        return coreApi.deleteNamespacedService(metadata.getName(), metadata.getNamespace(), null, null, null, null, null, new V1DeleteOptions());
    }

    public Completable deleteIngress(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) {
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                for(ExtensionsV1beta1Ingress ingress : listNamespacedIngress(namespace, null, labelSelector)) {
                    try {
                        deleteIngress(ingress);
                        logger.debug("Deleted Ingress namespace={} name={}", ingress.getMetadata().getNamespace(), ingress.getMetadata().getName());
                    } catch (final JsonSyntaxException e) {
                        logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                    }
                }
            }
        });
    }

    public V1Status deleteIngress(final ExtensionsV1beta1Ingress ingress) throws ApiException {
            final V1ObjectMeta metadata = ingress.getMetadata();
            return extensionsV1beta1Api.deleteNamespacedIngress(metadata.getName(), metadata.getNamespace(), null, null, null, null, null, new V1DeleteOptions());
    }

    public V1Status deleteConfigMap(final V1ConfigMap configMap) throws ApiException {
        final V1ObjectMeta configMapMetadata = configMap.getMetadata();
        return coreApi.deleteNamespacedConfigMap(configMapMetadata.getName(), configMapMetadata.getNamespace(), null, null, null, null, null, new V1DeleteOptions());
    }

    public Completable deleteStatefulSet(final V1StatefulSet statefulSet) throws ApiException {
        return deleteResource(() -> {
            V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");

//        //Scale the statefulset down to zero (https://github.com/kubernetes/client-go/issues/91)
//        statefulSet.getSpec().setReplicas(0);
//
//        appsApi.replaceNamespacedStatefulSet(statefulSet.getMetadata().getName(), statefulSet.getMetadata().getNamespace(), statefulSet, null, null);
//
//        while (true) {
//            int currentReplicas = appsApi.readNamespacedStatefulSet(statefulSet.getMetadata().getName(), statefulSet.getMetadata().getNamespace(), null, null, null).getStatus().getReplicas();
//            if (currentReplicas == 0)
//                break;
//
//            Thread.sleep(50);
//        }
//
//        logger.debug("done with scaling to 0");

            V1Status v1Status = null;
            try {
                final V1ObjectMeta statefulSetMetadata = statefulSet.getMetadata();
                v1Status = appsApi.deleteNamespacedStatefulSet(statefulSetMetadata.getName(), statefulSetMetadata.getNamespace(), null, null, null, null, null, deleteOptions);
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
            }
            return v1Status;
        });
    }

    public Completable deleteDeployment(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) {
        return deleteResource(() -> {
            for (V1Deployment deployment : listNamespacedDeployment(namespace, null, labelSelector)) {
                try {
                    deleteDeployment(deployment.getMetadata());
                    logger.debug("Deleted Deployment namespace={} name={}", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
                } catch (final JsonSyntaxException e) {
                    logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                }
            }
            return (V1Status) null;
        });
    }

    public Single<DataCenter> deleteDataCenter(final V1ObjectMeta metadata) throws ApiException {
        return Single.fromCallable(new Callable<DataCenter>() {
            @Override
            public DataCenter call() throws Exception {
                try {
                    logger.debug("Deleting DataCenter namespace={} name={}", metadata.getNamespace(), metadata.getName());
                    V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
                    Call call = customObjectsApi.deleteNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                            metadata.getNamespace(), DataCenter.PLURAL, metadata.getName(), deleteOptions,
                            null, null, "Foreground", null);
                    final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                    return apiResponse.getData();
                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", metadata.getName(), metadata.getNamespace());
                        throw new NoSuchElementException("elassandradatacenter="+metadata.getName()+"/"+metadata.getNamespace()+" not found");
                    }
                    throw e;
                }
            }
        });
    }

    // see https://github.com/kubernetes-client/java/issues/86
    public V1Status deleteDeployment(final V1ObjectMeta metadata) throws ApiException {
        logger.debug("Deleting Deployment namespace={} name={}", metadata.getNamespace(), metadata.getName());
        V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        return appsApi.deleteNamespacedDeployment(metadata.getName(), metadata.getNamespace(), null, null, null, null, null, deleteOptions);
    }

    public V1Status deletePodDisruptionBudget(final V1ObjectMeta metadata) throws ApiException {
        logger.debug("Deleting PodDisruptionBudget namespace={} name={}", metadata.getNamespace(), metadata.getName());
        V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        return policyV1beta1Api.deleteNamespacedPodDisruptionBudget(metadata.getName(), metadata.getNamespace(), null, null, null, null, null, deleteOptions);
    }

    public Completable deleteService(final String name, final String namespace) throws ApiException {
        return deleteResource(() -> {
            V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
            return coreApi.deleteNamespacedService(name, namespace, null, null, null, null, null, deleteOptions);
        });
    }

    public Completable deletePersistentVolumeClaim(final V1PersistentVolumeClaim persistentVolumeClaim) throws ApiException {
        return deleteResource(() -> {
            final V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
            final String pvcName = persistentVolumeClaim.getMetadata().getName();
            V1Status v1Status = null;
            try {
                logger.debug("Deleting PVC name={}", pvcName);
                v1Status = coreApi.deleteNamespacedPersistentVolumeClaim(pvcName, persistentVolumeClaim.getMetadata().getNamespace(), null, null, null, null, null, deleteOptions);
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
            }
            return v1Status;
        });
    }


    static class ResourceListIterable<T> implements Iterable<T> {
        interface Page<T> {
            Collection<T> items();

            Page<T> nextPage() throws ApiException;
        }

        private Page<T> firstPage;
        ResourceListIterable(final Page<T> firstPage) {
            this.firstPage = firstPage;
        }

        @Override
        public Iterator<T> iterator() {
            return Iterators.concat(new AbstractIterator<Iterator<T>>() {
                Page<T> currentPage = firstPage;

                @Override
                protected Iterator<T> computeNext() {
                    if (currentPage == null)
                        return endOfData();

                    final Iterator<T> iterator = currentPage.items().iterator();

                    try {
                        currentPage = currentPage.nextPage();

                    } catch (final ApiException e) {
                        throw new RuntimeException(e);
                    }

                    return iterator;
                }
            });
        }
    }

    public Iterable<V1Pod> listNamespacedPods(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1PodPage implements ResourceListIterable.Page<V1Pod> {
            private final V1PodList podList;

            private V1PodPage(final String continueToken) throws ApiException {
                podList = coreApi.listNamespacedPod(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1Pod> items() {
                return podList.getItems();
            }

            @Override
            public V1PodPage nextPage() throws ApiException {
                final String continueToken = podList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1PodPage(continueToken);
            }
        }
        return new ResourceListIterable<>( new V1PodPage(null));
    }


    public Iterable<V1PersistentVolumeClaim> listNamespacedPodsPersitentVolumeClaims(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1PersistentVolumeClaimPage implements ResourceListIterable.Page<V1PersistentVolumeClaim> {
            private final V1PersistentVolumeClaimList podList;

            private V1PersistentVolumeClaimPage(final String continueToken) throws ApiException {
                podList = coreApi.listNamespacedPersistentVolumeClaim(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1PersistentVolumeClaim> items() {
                return podList.getItems();
            }

            @Override
            public V1PersistentVolumeClaimPage nextPage() throws ApiException {
                final String continueToken = podList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1PersistentVolumeClaimPage(continueToken);
            }
        }
        return new ResourceListIterable<>( new V1PersistentVolumeClaimPage(null));
    }

    public Iterable<DataCenter> listNamespacedDataCenters(final String namespace, @Nullable final String labelSelector) throws ApiException {
        class V1DataCenterPage implements ResourceListIterable.Page<DataCenter> {
            private final DataCenterList dcList;

            private V1DataCenterPage(final String continueToken) throws ApiException {
                final Call call = customObjectsApi.listClusterCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        DataCenter.PLURAL, null, null, null, labelSelector, null, null, null, null, null);
                final ApiResponse<DataCenterList> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenterList.class);
                dcList = apiResponse.getData();
            }

            @Override
            public Collection<DataCenter> items() {
                return dcList.getItems();
            }

            @Override
            public ResourceListIterable.Page<DataCenter> nextPage() throws ApiException {
                final String continueToken = dcList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1DataCenterPage(continueToken);
            }
        }
        return new ResourceListIterable<>(new V1DataCenterPage(null));
    }


    public Iterable<V1StatefulSet> listNamespacedStatefulSets(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1StatefulSetPage implements ResourceListIterable.Page<V1StatefulSet> {
            private final V1StatefulSetList statefulSetList;

            private V1StatefulSetPage(final String continueToken) throws ApiException {
                statefulSetList = appsApi.listNamespacedStatefulSet(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1StatefulSet> items() {
                return statefulSetList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1StatefulSet> nextPage() throws ApiException {
                final String continueToken = statefulSetList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1StatefulSetPage(continueToken);
            }
        }
        return new ResourceListIterable<>(new V1StatefulSetPage(null));
    }


    public Iterable<V1ConfigMap> listNamespacedConfigMaps(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1ConfigMapPage implements ResourceListIterable.Page<V1ConfigMap> {
            private final V1ConfigMapList configMapList;

            private V1ConfigMapPage(final String continueToken) throws ApiException {
                configMapList = coreApi.listNamespacedConfigMap(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1ConfigMap> items() {
                return configMapList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1ConfigMap> nextPage() throws ApiException {
                final String continueToken = configMapList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1ConfigMapPage(continueToken);
            }
        }
        return new ResourceListIterable<>(new V1ConfigMapPage(null));
    }

    public Iterable<V1Secret> listNamespacedSecret(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1SecretPage implements ResourceListIterable.Page<V1Secret> {
            private final V1SecretList secretList;

            private V1SecretPage(final String continueToken) throws ApiException {
                secretList = coreApi.listNamespacedSecret(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1Secret> items() {
                return secretList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1Secret> nextPage() throws ApiException {
                final String continueToken = secretList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1SecretPage(continueToken);
            }
        }
        return new ResourceListIterable<>(new V1SecretPage(null));
    }

    public Iterable<V1ServiceAccount> listNamespacedServiceAccount(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1ServiceAccountPage implements ResourceListIterable.Page<V1ServiceAccount> {
            private final V1ServiceAccountList secretList;

            private V1ServiceAccountPage(final String continueToken) throws ApiException {
                secretList = coreApi.listNamespacedServiceAccount(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1ServiceAccount> items() {
                return secretList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1ServiceAccount> nextPage() throws ApiException {
                final String continueToken = secretList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1ServiceAccountPage(continueToken);
            }
        }
        return new ResourceListIterable<>(new V1ServiceAccountPage(null));
    }

    public Iterable<V1Service> listNamespacedServices(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1ServicePage implements ResourceListIterable.Page<V1Service> {
            private final V1ServiceList serviceList;

            private V1ServicePage(final String continueToken) throws ApiException {
                serviceList = coreApi.listNamespacedService(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1Service> items() {
                return serviceList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1Service> nextPage() throws ApiException {
                final String continueToken = serviceList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1ServicePage(continueToken);
            }
        }

        final V1ServicePage firstPage = new V1ServicePage(null);
        return new ResourceListIterable<>(firstPage);
    }

    public Iterable<ExtensionsV1beta1Ingress> listNamespacedIngress(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1IngressPage implements ResourceListIterable.Page<ExtensionsV1beta1Ingress> {
            private final ExtensionsV1beta1IngressList ingressList;

            private V1IngressPage(final String continueToken) throws ApiException {
                ingressList = extensionsV1beta1Api.listNamespacedIngress(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, false);
            }

            @Override
            public Collection<ExtensionsV1beta1Ingress> items() {
                return ingressList.getItems();
            }

            @Override
            public ResourceListIterable.Page<ExtensionsV1beta1Ingress> nextPage() throws ApiException {
                final String continueToken = ingressList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1IngressPage(continueToken);
            }
        }

        final V1IngressPage firstPage = new V1IngressPage(null);
        return new ResourceListIterable<>(firstPage);
    }

    public Iterable<Task> listNamespacedTask(final String namespace, @Nullable final String labelSelector) throws ApiException {
        class TaskPage implements ResourceListIterable.Page<Task> {
            private final TaskList taskList;

            private TaskPage(final String continueToken) throws ApiException {
                Call call = customObjectsApi.listNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION, namespace, Task.PLURAL,
                        "false", null, null, labelSelector, null, null, null, null, null);
                Type localVarReturnType = new TypeToken<TaskList>(){}.getType();
                ApiResponse<TaskList> resp = customObjectsApi.getApiClient().execute(call, localVarReturnType);
                taskList = resp.getData();
            }

            @Override
            public List<Task> items() {
                return taskList.getItems();
            }

            @Override
            public ResourceListIterable.Page<Task> nextPage() throws ApiException {
                final String continueToken = taskList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new TaskPage(continueToken);
            }
        }

        final TaskPage firstPage = new TaskPage(null);
        return new ResourceListIterable<>(firstPage);
    }


    public Iterable<V1Deployment> listNamespacedDeployment(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1DeploymentPage implements ResourceListIterable.Page<V1Deployment> {
            private final V1DeploymentList deploymentList;

            private V1DeploymentPage(final String continueToken) throws ApiException {
                try {
                    deploymentList = appsApi.listNamespacedDeployment(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
                } catch(ApiException e) {
                    logger.warn("Failed to list deployments in namespace="+namespace+" labelSelector="+labelSelector, e);
                    throw e;
                }
            }

            @Override
            public Collection<V1Deployment> items() {
                return deploymentList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1Deployment> nextPage() throws ApiException {
                final String continueToken = deploymentList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1DeploymentPage(continueToken);
            }
        }

        final V1DeploymentPage firstPage = new V1DeploymentPage(null);
        return new ResourceListIterable<>(firstPage);
    }

    public Single<DataCenter> readDatacenter(final Key key) throws ApiException {
        return Single.fromCallable(new Callable<DataCenter>() {
            @Override
            public DataCenter call() throws Exception {
                try {
                    final Call call = customObjectsApi.getNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                            key.getNamespace(), DataCenter.PLURAL, key.getName(), null);
                    final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                    return apiResponse.getData();
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", key.name, key.namespace);
                        throw new NoSuchElementException("elassandradatacenter="+key+" not found");
                    }
                    throw e;
                }
            }
        });
    }

    public Single<Optional<Task>> readTask(final String namespace, final String name) throws ApiException {
        return Single.fromCallable(new Callable<Optional<Task>>() {
            @Override
            public Optional<Task> call() throws Exception {
                try {
                    final Call call = customObjectsApi.getNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                            namespace, Task.PLURAL, name, null);
                    final ApiResponse<Task> apiResponse = customObjectsApi.getApiClient().execute(call, Task.class);
                    return Optional.ofNullable(apiResponse.getData());
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandratask not found for task={} in namespace={}", name, namespace);
                    }
                    return Optional.<Task>empty();
                }
            }
        });
    }

    /*
    public Single<DataCenter> updateDataCenter(final DataCenter dc) throws ApiException {
        return Single.fromCallable( () ->{
            try {
                final Call call = customObjectsApi.patchNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                        dc.getMetadata().getNamespace(), DataCenter.PLURAL, dc.getMetadata().getName(), dc, null);
                final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                return apiResponse.getData();
            } catch(ApiException e) {
                if (e.getCode() == 404) {
                    logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", dc.getMetadata().getName(), dc.getMetadata().getNamespace());
                    throw new NoSuchElementException("elassandradatacenter="+dc.id()+" not found");
                }
                throw e;
            }
        });
    }
     */

    public Single<Object> updateDataCenterStatus(final DataCenter dc, final DataCenterStatus dcStatus) throws ApiException {
        // read before write to avoid 409 conflict
        Key key = new Key(dc.getMetadata());
        return readDatacenter(key)
                .map(currentDc -> {
                    currentDc.setStatus(dcStatus);
                    return currentDc;
                })
                .flatMap(currentDc ->
                        Single.fromCallable(() -> {
                                customObjectsApi.replaceNamespacedCustomObjectStatus(StrapdataCrdGroup.GROUP, DataCenter.VERSION,
                                        dc.getMetadata().getNamespace(), DataCenter.PLURAL, dc.getMetadata().getName(), currentDc);
                            return dataCenterStatusCache.put(key, currentDc.getStatus());
                        })
                );
    }


    public Single<Object> updateTaskStatus(final Task task) throws ApiException {
        // read before write to avoid 409 conflict
        return readTask(task.getMetadata().getNamespace(), task.getMetadata().getName())
                .map(optionalTask -> {
                    if (optionalTask.isPresent()) {
                        Task taskToUdate = optionalTask.get();
                        taskToUdate.setStatus(task.getStatus());
                        return taskToUdate;
                    }
                    return task;
                })
                .flatMap(task2 -> Single.fromCallable(() ->
                        customObjectsApi.replaceNamespacedCustomObjectStatus(StrapdataCrdGroup.GROUP, Task.VERSION,
                            task2.getMetadata().getNamespace(), Task.PLURAL, task2.getMetadata().getName(), task2)));
    }

    /*
    public Completable updateTaskStatus(final Task task) throws ApiException {
        return Completable.fromCallable(new Callable<Task>() {
            @Override
            public Task call() throws Exception {
                try {
                    assert task.getStatus() != null : "task status is null";

                    ApiClient debuggableApiClient = ClientBuilder.standard().build();
                    debuggableApiClient.setDebugging(true);
                    CustomObjectsApi debuggableCustomObjectsApi = new CustomObjectsApi(debuggableApiClient);

                    final Call call = debuggableCustomObjectsApi.replaceNamespacedCustomObjectStatusCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                            task.getMetadata().getNamespace(), Task.PLURAL, task.getMetadata().getName(), task, null, null);
                    final ApiResponse<Task> apiResponse = debuggableCustomObjectsApi.getApiClient().execute(call, Task.class);
                    return apiResponse.getData();
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandratask not found for task={} in namespace={}", task.getMetadata().getName(), task.getMetadata().getNamespace());
                    }
                    throw e;
                }
            }
        });
    }
    */

    public Single<Task> createTask(Task task) throws ApiException {
        return Single.fromCallable(new Callable<Task>() {
            @Override
            public Task call() throws Exception {
                try {
                    final Call call = customObjectsApi.createNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                            task.getMetadata().getNamespace(), Task.PLURAL, task, null, null);
                    final ApiResponse<Task> apiResponse = customObjectsApi.getApiClient().execute(call, Task.class);
                    return apiResponse.getData();
                } catch(ApiException e) {
                    logger.warn("Unable to create task name={} in namespace={}, code={} - reason={}", task.getMetadata().getName(), task.getMetadata().getNamespace(), e.getCode(), e.getResponseBody());
                    throw e;
                }
            }
        });
    }

    public Completable deleteTasks(String namespace, @Nullable final String labelSelector) throws ApiException {
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                for (Task task : listNamespacedTask(namespace, labelSelector)) {
                    try {
                        deleteTask(task.getMetadata()).blockingGet();
                        logger.debug("Deleted task namespace={} name={}", task.getMetadata().getNamespace(), task.getMetadata().getName());
                    } catch (final JsonSyntaxException e) {
                        logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
                    }
                }
            }
        });
    }

    public Single<Task> deleteTask(final V1ObjectMeta metadata) throws ApiException {
        return Single.fromCallable(new Callable<Task>() {
            @Override
            public Task call() throws Exception {
                try {
                    logger.debug("Deleting DataCenter namespace={} name={}", metadata.getNamespace(), metadata.getName());
                    V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
                    Call call = customObjectsApi.deleteNamespacedCustomObjectCall(StrapdataCrdGroup.GROUP, Task.VERSION,
                            metadata.getNamespace(), Task.PLURAL, metadata.getName(), deleteOptions,
                            null, null, "Foreground", null);
                    final ApiResponse<Task> apiResponse = customObjectsApi.getApiClient().execute(call, Task.class);
                    return apiResponse.getData();
                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandratasks not found for task={} in namespace={}", metadata.getName(), metadata.getNamespace());
                        throw new NoSuchElementException("elassandratasks="+metadata.getName()+"/"+metadata.getNamespace()+" not found");
                    }
                    throw e;
                }
            }
        });
    }

    public Single<Task> createTask(DataCenter dc, String taskType, Consumer<TaskSpec> modifier) throws ApiException {
            final String name = OperatorNames.generateTaskName(dc, taskType);
            final Task task = Task.fromDataCenter(name, dc);
            modifier.accept(task.getSpec());
            return this.createTask(task);
    }

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
        V1PodList pods = coreApi.listNamespacedPod(namespace, null, null, null, null, labelSelector, null, null, null, null);
        return (pods != null && !pods.getItems().isEmpty());
    }

    public boolean deletePod(final String namespace, final String podname) throws ApiException {
        V1DeleteOptions v1DeleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        V1Status status = coreApi.deleteNamespacedPod(podname, namespace, null, null, null, null, null, v1DeleteOptions);
        return status.getCode() == 200;
    }


}
