package com.strapdata.strapkop.k8s;

import com.google.common.base.Strings;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.backup.*;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterList;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskList;
import com.strapdata.model.k8s.task.TaskPhase;
import com.strapdata.model.k8s.task.TaskSpec;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.reconcilier.TaskReconcilier;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.apis.ExtensionsV1beta1Api;
import io.kubernetes.client.models.*;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.scheduling.$DefaultTaskExceptionHandlerDefinitionClass;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.strapdata.strapkop.utils.CloudStorageSecretsKeys.*;

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

    @FunctionalInterface
    public interface ApiCallable {
        void call() throws ApiException;
    }

    public static <T> Single<T> createOrReplaceResource(final Callable<T> createResourceCallable, final Callable<T> replaceResourceCallable) throws ApiException {
        return Single.fromCallable(new Callable<T>() {
            @Override
            public T call() throws Exception {
                try {
                    logger.trace("Attempting to create resource.");
                    return createResourceCallable.call();
                } catch (final ApiException e) {
                    if (e.getCode() != 409)
                        throw e;

                    logger.trace("Resource already exists. Attempting to replace.");
                    return replaceResourceCallable.call();
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

    public Single<V1Service> createOrReplaceNamespacedService(final V1Service service) throws ApiException {
        final String namespace = service.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1Service service2 = coreApi.createNamespacedService(namespace, service, null, null, null);
                    logger.debug("Created namespaced Service={}", service.getMetadata().getName());
                    return service2;
                },
                () -> {
        // temporarily disable service replace call to fix issue #41 since service can't be customized right now
        //                        coreApi.replaceNamespacedService(service.getMetadata().getName(), service.getMetadata().getNamespace(), service, null, null);
        //                        logger.debug("Replaced namespaced Service.");
                    return service;
                }
        );
    }

    public Single<V1Service> createNamespacedService(final V1Service service) throws ApiException {
        final String namespace = service.getMetadata().getNamespace();
        return Single.fromCallable(
                () -> {
                    try {
                        V1Service service2 = coreApi.createNamespacedService(namespace, service, null, null, null);
                        logger.debug("Created namespaced Service={}", service.getMetadata().getName());
                        return service2;
                    } catch(ApiException e) {
                        logger.warn("Created namespaced Service={} in namespace={} error:"+e.getMessage(),
                                service.getMetadata().getName(), service.getMetadata().getNamespace());
                        throw e;
                    }
                });
    }

    public Single<V1beta1Ingress> createOrReplaceNamespacedIngress(final V1beta1Ingress ingress) throws ApiException {
        final String namespace = ingress.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1beta1Ingress ingress2 = extensionsV1beta1Api.createNamespacedIngress(namespace, ingress, null, null, null);
                    logger.debug("Created namespaced Ingress={}", ingress.getMetadata().getName());
                    return ingress2;
                },
                () -> {
                    // temporarily disable service replace call to fix issue #41 since service can't be customized right now
//                        coreApi.replaceNamespacedService(service.getMetadata().getName(), service.getMetadata().getNamespace(), service, null, null);
//                        logger.debug("Replaced namespaced Service.");
                    return ingress;
                }
        );
    }

    public Single<V1ConfigMap> createOrReplaceNamespacedConfigMap(final V1ConfigMap configMap) throws ApiException {
        final String namespace = configMap.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1ConfigMap configMap2 = coreApi.createNamespacedConfigMap(namespace, configMap, null, null, null);
                    logger.debug("Created namespaced ConfigMap={}", configMap.getMetadata().getName());
                    return configMap2;
                },
                () -> {
                    V1ConfigMap configMap2 = coreApi.replaceNamespacedConfigMap(configMap.getMetadata().getName(), namespace, configMap, null, null);
                    logger.debug("Replaced namespaced ConfigMap={}", configMap.getMetadata().getName());
                    return configMap2;
                }
        );
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
                    }
                    throw e;
                }
            }
        });
    }

    public Single<V1Deployment> createOrReplaceNamespacedDeployment(final V1Deployment deployment) throws ApiException {
        final String namespace = deployment.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1Deployment deployment2 = appsApi.createNamespacedDeployment(namespace, deployment, null, null, null);
                    logger.debug("Created namespaced Deployment={} in namespace={}", deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                    return deployment2;
                },
                () -> {
                    V1Deployment deployment2 = appsApi.replaceNamespacedDeployment(deployment.getMetadata().getName(), namespace, deployment, null, null);
                    logger.debug("Replaced namespaced Deployment in namespace={}", deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                    return deployment2;
                }
        );
    }

    public Single<V1StatefulSet> createOrReplaceNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1StatefulSet statefulSet2 = appsApi.createNamespacedStatefulSet(namespace, statefulset, null, null, null);
                    logger.debug("Created namespaced Deployment={} in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                    return statefulSet2;
                },
                () -> {
                    V1StatefulSet statefulSet2 = appsApi.replaceNamespacedStatefulSet(statefulset.getMetadata().getName(), namespace, statefulset, null, null);
                    logger.debug("Replaced namespaced Deployment in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                    return statefulSet2;
                }
        );
    }

    public Single<V1StatefulSet> createNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return Single.fromCallable(
                () -> {
                    V1StatefulSet statefulSet2 = appsApi.createNamespacedStatefulSet(namespace, statefulset, null, null, null);
                    logger.debug("Created namespaced Deployment={} in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                    return statefulSet2;
                });
    }

    public Single<V1StatefulSet> replaceNamespacedStatefulSet(final V1StatefulSet statefulset) throws ApiException {
        final String namespace = statefulset.getMetadata().getNamespace();
        return Single.fromCallable(() -> {
                    V1StatefulSet statefulSet2 = appsApi.replaceNamespacedStatefulSet(statefulset.getMetadata().getName(), namespace, statefulset, null, null);
                    logger.debug("Replaced namespaced Deployment in namespace={}", statefulset.getMetadata().getName(), statefulset.getMetadata().getNamespace());
                    return statefulSet2;
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
                            logger.warn("statefulset namespace={} name={} not found", namespace, name);
                        }
                        throw e;
                    }

                }
        );
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
                }
                throw e;
            }
    }

    public Single<V1Secret> createOrReplaceNamespacedSecret(final V1Secret secret) throws ApiException {
        final String namespace = secret.getMetadata().getNamespace();
        return createOrReplaceResource(
                () -> {
                    V1Secret secret2 = coreApi.createNamespacedSecret(namespace, secret, null, null, null);
                    logger.debug("Created namespaced secret={}", secret.getMetadata().getName());
                    return secret2;
                },
                () -> {
                    V1Secret secret2 = coreApi.replaceNamespacedSecret(secret.getMetadata().getName(), namespace, secret, null, null);
                    logger.debug("Replaced namespaced secret={}", secret.getMetadata().getName());
                    return secret2;
                }
        );
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
                    logger.warn("Created namespaced secret={}", secret2.getMetadata().getName());
                    return secret2;
                }
        );
    }

    /**
     * Read secret and check if the content match the storage provider to avoid issue when side car will use it.
     * if secret doesn't exist exception is thrown and catch as task failure.
     * @param namespace
     * @param secretRef
     * @param provider
     * @return
     */
    public CloudStorageSecret readAndValidateStorageSecret(final String namespace, final String secretRef, final StorageProvider provider) {
        if (StringUtils.isEmpty(secretRef)) {
            throw new StrapkopException("Unable to perform backup tasks without a secret reference");
        }

        V1Secret secret = readNamespacedSecret(namespace, secretRef).blockingGet();
        switch (provider) {
            case AZURE_BLOB:
                if (!(secret.getData().containsKey(AZURE_STORAGE_ACCOUNT_NAME) && secret.getData().containsKey(AZURE_STORAGE_ACCOUNT_KEY))) {
                    throw new StrapkopException("Azure blob secret configured but one of values is missing (storage-key, storage-account)");
                } else {
                    logger.info("Azure blob secret configured for backup");
                    return  AzureCloudStorageSecret.builder()
                            .accountKey(new String(secret.getData().get(AZURE_STORAGE_ACCOUNT_KEY), Charset.forName("UTF-8")))
                            .accountName(new String(secret.getData().get(AZURE_STORAGE_ACCOUNT_NAME), Charset.forName("UTF-8")))
                            .build();
                }
            case AWS_S3:
                if(!(secret.getData().containsKey(AWS_ACCESS_KEY_REGION)
                        && secret.getData().containsKey(AWS_ACCESS_KEY_ID)
                        && secret.getData().containsKey(AWS_ACCESS_KEY_SECRET))) {
                    throw new StrapkopException("AWS blob secret configured but one of values is missing (region, access-key, secret-key)");
                } else {
                    logger.info("AWS blob secret configured for backup");
                    return AWSCloudStorageSecret.builder()
                            .accessKeyId(new String(secret.getData().get(AWS_ACCESS_KEY_ID), Charset.forName("UTF-8")))
                            .accessKeySecret(new String(secret.getData().get(AWS_ACCESS_KEY_SECRET), Charset.forName("UTF-8")))
                            .region(new String(secret.getData().get(AWS_ACCESS_KEY_REGION), Charset.forName("UTF-8")))
                            .build();
                }
            case GCP_BLOB:
                if (!(secret.getData().containsKey(GCP_JSON) && secret.getData().containsKey(GCP_PROJECT_ID))) {
                    throw new StrapkopException("GCP blob secret configured but gcp.json or project_id is missing");
                } else {
                    logger.info("GCP blob secret configured for backup");
                    return  GCPCloudStorageSecret.builder()
                            .jsonCredentials(secret.getData().get(GCP_JSON))
                            .projectId(new String(secret.getData().get(GCP_PROJECT_ID), Charset.forName("UTF-8")))
                            .build();
                }
        }

        throw new StrapkopException(provider + " provider isn't supported");
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
        return coreApi.deleteNamespacedService(metadata.getName(), metadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
    }

    public Completable deleteIngress(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) {
        return Completable.fromAction(new Action() {
            @Override
            public void run() throws Exception {
                for(V1beta1Ingress ingress : listNamespacedIngress(namespace, null, labelSelector)) {
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

    public V1Status deleteIngress(final V1beta1Ingress ingress) throws ApiException {
            final V1ObjectMeta metadata = ingress.getMetadata();
            return extensionsV1beta1Api.deleteNamespacedIngress(metadata.getName(), metadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
    }

    public V1Status deleteConfigMap(final V1ConfigMap configMap) throws ApiException {
        final V1ObjectMeta configMapMetadata = configMap.getMetadata();
        return coreApi.deleteNamespacedConfigMap(configMapMetadata.getName(), configMapMetadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
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
                v1Status = appsApi.deleteNamespacedStatefulSet(statefulSetMetadata.getName(), statefulSetMetadata.getNamespace(), deleteOptions, null, null, null, false, "Foreground");
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
                    Call call = customObjectsApi.deleteNamespacedCustomObjectAsync("stable.strapdata.com", "v1",
                            metadata.getNamespace(), "elassandradatacenters", metadata.getName(), deleteOptions, null, null, "Foreground", null);
                    final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                    return apiResponse.getData();
                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", metadata.getName(), metadata.getNamespace());
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
        return appsApi.deleteNamespacedDeployment(metadata.getName(), metadata.getNamespace(), deleteOptions, null, null, null, null, "Foreground");
     }
    
    public Completable deleteService(final String name, final String namespace) throws ApiException {
        return deleteResource(() -> {
            V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
            return coreApi.deleteNamespacedService(name, namespace, deleteOptions, null, null, null, false, "Foreground");
        });
    }
    
    public Completable deletePersistentVolumeClaim(final V1PersistentVolumeClaim persistentVolumeClaim) throws ApiException {
        return deleteResource(() -> {
            final V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");

            final String pvcName = persistentVolumeClaim.getMetadata().getName();
            final V1PersistentVolumeClaim pvc = coreApi.readNamespacedPersistentVolumeClaim(pvcName, persistentVolumeClaim.getMetadata().getNamespace(), null, null, null);

            V1Status v1Status = null;
            try {
                logger.debug("Deleting PVC name={}", pvcName);
                v1Status = coreApi.deleteNamespacedPersistentVolumeClaim(pvcName, persistentVolumeClaim.getMetadata().getNamespace(), deleteOptions, null, null, null, null, "Foreground");
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
                final Call call = customObjectsApi.listClusterCustomObjectCall("stable.strapdata.com", "v1",
                        "elassandradatacenters", null, labelSelector, null, null, null, null);
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

    public Iterable<V1beta1Ingress> listNamespacedIngress(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1IngressPage implements ResourceListIterable.Page<V1beta1Ingress> {
            private final V1beta1IngressList ingressList;

            private V1IngressPage(final String continueToken) throws ApiException {
                ingressList = extensionsV1beta1Api.listNamespacedIngress(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<V1beta1Ingress> items() {
                return ingressList.getItems();
            }

            @Override
            public ResourceListIterable.Page<V1beta1Ingress> nextPage() throws ApiException {
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
                com.squareup.okhttp.Call call = customObjectsApi.listNamespacedCustomObjectCall(Task.NAME, Task.VERSION, namespace, Task.PLURAL, "false", labelSelector, null, Boolean.FALSE, null, null);
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
                    final Call call = customObjectsApi.getNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                            key.getNamespace(), "elassandradatacenters", key.getName(), null, null);
                    final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                    return apiResponse.getData();
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", key.name, key.namespace);
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
                    final Call call = customObjectsApi.getNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                            namespace, "elassandratasks", name, null, null);
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

    public Single<DataCenter> updateDataCenter(final DataCenter dc) throws ApiException {
        return Single.fromCallable( () ->{
            try {
                final Call call = customObjectsApi.patchNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                        dc.getMetadata().getNamespace(), "elassandradatacenters", dc.getMetadata().getName(), dc, null, null);
                final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
                return apiResponse.getData();
            } catch(ApiException e) {
                if (e.getCode() == 404) {
                    logger.warn("elassandradatacenter not found for datacenter={} in namespace={}", dc.getMetadata().getName(), dc.getMetadata().getNamespace());
                }
                throw e;
            }
        });
    }

    public Single<Object> updateDataCenterStatus(final DataCenter dc) throws ApiException {
        return Single.fromCallable(() -> {
                return customObjectsApi.replaceNamespacedCustomObjectStatus("stable.strapdata.com", "v1",
                        dc.getMetadata().getNamespace(), "elassandradatacenters", dc.getMetadata().getName(), dc);
        });
    }

    public Completable updateTaskStatus(TaskReconcilier.TaskWrapper taskWrapper, TaskPhase phase) throws ApiException {
        taskWrapper.getTask().getStatus().setPhase(phase);
        return updateTaskStatus(taskWrapper);
    }


    public Completable updateTaskStatus(TaskReconcilier.TaskWrapper taskWrapper) throws ApiException {
        return Completable.fromCallable(new Callable<TaskReconcilier.TaskWrapper>() {
            /**
             * Computes a result, or throws an exception if unable to do so.
             *
             * @return computed result
             * @throws Exception if unable to compute a result
             */
            @Override
            public TaskReconcilier.TaskWrapper call() throws Exception {
                final Task task = taskWrapper.getTask();
                try {
                    final Call call = customObjectsApi.replaceNamespacedCustomObjectStatusCall("stable.strapdata.com", "v1",
                            task.getMetadata().getNamespace(), "elassandratasks", task.getMetadata().getName(), task, null, null);
                    final ApiResponse<Task> apiResponse = customObjectsApi.getApiClient().execute(call, Task.class);
                    taskWrapper.updateTaskRef(apiResponse.getData());
                    return taskWrapper;
                } catch(ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandratask not found for task={} in namespace={}", task.getMetadata().getName(), task.getMetadata().getNamespace());
                    }
                    throw e;
                }
            }
        });
    }

    public Single<Task> createTask(Task task) throws ApiException {
        return Single.fromCallable(new Callable<Task>() {
            @Override
            public Task call() throws Exception {
                try {
                    final Call call = customObjectsApi.createNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                            task.getMetadata().getNamespace(), "elassandratasks", task, null, null, null);
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
                        deleteTask(task.getMetadata());
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
                    Call call = customObjectsApi.deleteNamespacedCustomObjectAsync("stable.strapdata.com", "v1",
                            metadata.getNamespace(), "elassandratasks", metadata.getName(), deleteOptions, null, null, "Foreground", null);
                    final ApiResponse<Task> apiResponse = customObjectsApi.getApiClient().execute(call, Task.class);
                    return apiResponse.getData();
                } catch (ApiException e) {
                    if (e.getCode() == 404) {
                        logger.warn("elassandratasks not found for task={} in namespace={}", metadata.getName(), metadata.getNamespace());
                    }
                    throw e;
                }
            }
        });
    }

    public Single<Task> createTask(DataCenter dc, String taskType, Consumer<TaskSpec> modifier, Map<String, String> labels) throws ApiException {
            final String name = OperatorNames.generateTaskName(dc, taskType);
            final Task task = Task.fromDataCenter(name, dc);
            modifier.accept(task.getSpec());
            if (labels != null) {
                Map<String, String> mdLabels = task.getMetadata().getLabels();
                if (mdLabels == null) {
                    mdLabels = new HashMap<>();
                    task.getMetadata().labels(mdLabels);
                }
                mdLabels.putAll(labels);
            }
            return this.createTask(task);
    }


}
