package com.strapdata.strapkop.k8s;

import com.google.common.base.Strings;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterators;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.Call;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.task.Task;
import com.strapdata.model.k8s.task.TaskList;
import com.strapdata.model.k8s.task.TaskSpec;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.apis.ExtensionsV1beta1Api;
import io.kubernetes.client.models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

@Singleton
public class K8sResourceUtils {
    private static final Logger logger = LoggerFactory.getLogger(K8sResourceUtils.class);
    
    @Inject
    private CoreV1Api coreApi;
    
    @Inject
    private AppsV1Api appsApi;
    
    @Inject
    private CustomObjectsApi customObjectsApi;
    
    @Inject
    private ExtensionsV1beta1Api extensionsV1beta1Api;

    @FunctionalInterface
    public interface ApiCallable {
        void call() throws ApiException;
    }

    public static void createOrReplaceResource(final ApiCallable createResourceCallable, final ApiCallable replaceResourceCallable) throws ApiException {
        try {
            logger.trace("Attempting to create resource.");
            createResourceCallable.call();
        } catch (final ApiException e) {
            if (e.getCode() != 409)
                throw e;

            logger.trace("Resource already exists. Attempting to replace.");
            replaceResourceCallable.call();
        }
    }

    public void createOrReplaceNamespacedService(final V1Service service) throws ApiException {
        final String namespace = service.getMetadata().getNamespace();
        logger.debug("Creating/replacing namespaced Service.");
        createOrReplaceResource(
                () -> {
                    coreApi.createNamespacedService(namespace, service, null, null, null);
                    logger.debug("Created namespaced Service.");
                },
                () -> {
                    // temporarily disable service replace call to fix issue #41 since service can't be customized right now
//                        coreApi.replaceNamespacedService(service.getMetadata().getName(), service.getMetadata().getNamespace(), service, null, null);
//                        logger.debug("Replaced namespaced Service.");
                }
        );
    }

    public void createOrReplaceNamespacedIngress(final V1beta1Ingress ingress) throws ApiException {
        final String namespace = ingress.getMetadata().getNamespace();
        logger.debug("Creating/replacing namespaced Ingress.");
        createOrReplaceResource(
                () -> {
                    extensionsV1beta1Api.createNamespacedIngress(namespace, ingress, null, null, null);
                    logger.debug("Created namespaced Ingress.");
                },
                () -> {
                    // temporarily disable service replace call to fix issue #41 since service can't be customized right now
//                        coreApi.replaceNamespacedService(service.getMetadata().getName(), service.getMetadata().getNamespace(), service, null, null);
//                        logger.debug("Replaced namespaced Service.");
                }
        );
    }

    public void createOrReplaceNamespacedConfigMap(final V1ConfigMap configMap) throws ApiException {
        final String namespace = configMap.getMetadata().getNamespace();
        logger.debug("Creating/replacing namespaced ConfigMap.");
        createOrReplaceResource(
                () -> {
                    coreApi.createNamespacedConfigMap(namespace, configMap, null, null, null);
                    logger.debug("Created namespaced ConfigMap.");
                },
                () -> {
                    coreApi.replaceNamespacedConfigMap(configMap.getMetadata().getName(), namespace, configMap, null, null);
                    logger.debug("Replaced namespaced ConfigMap.");
                }
        );
    }
    
    public void createOrReplaceNamespacedDeployment(final V1Deployment deployment) throws ApiException {
        final String namespace = deployment.getMetadata().getNamespace();
        logger.debug("Creating/replacing namespaced Deployment.");
        createOrReplaceResource(
                () -> {
                    appsApi.createNamespacedDeployment(namespace, deployment, null, null, null);
                    logger.debug("Created namespaced Deployment.");
                },
                () -> {
                    appsApi.replaceNamespacedDeployment(deployment.getMetadata().getName(), namespace, deployment, null, null);
                    logger.debug("Replaced namespaced Deployment.");
                }
        );
    }

    public void createOrReplaceNamespacedSecret(final V1Secret secret) throws ApiException {
        final String namespace = secret.getMetadata().getNamespace();
        logger.debug("Creating/replacing namespaced secret.");
        createOrReplaceResource(
                () -> {
                    coreApi.createNamespacedSecret(namespace, secret, null, null, null);
                    logger.debug("Created namespaced Deployment.");
                },
                () -> {
                    coreApi.replaceNamespacedSecret(secret.getMetadata().getName(), namespace, secret, null, null);
                    logger.debug("Replaced namespaced Deployment.");
                }
        );
    }

    public void deleteService(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        listNamespacedServices(namespace, null, labelSelector).forEach(service -> {
            try {
                deleteService(service);
                logger.debug("Deleted Service namespace={} name={}", service.getMetadata().getNamespace(), service.getMetadata().getName());
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Service. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
            } catch (final ApiException e) {
                logger.error("Failed to delete Service.", e);
            }
        });
    }

    public void deleteService(final V1Service service) throws ApiException {
        final V1ObjectMeta metadata = service.getMetadata();
        coreApi.deleteNamespacedService(metadata.getName(), metadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
    }

    public void deleteIngress(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        listNamespacedIngress(namespace, null, labelSelector).forEach(ingress -> {
            try {
                deleteIngress(ingress);
                logger.debug("Deleted Ingress namespace={} name={}", ingress.getMetadata().getNamespace(), ingress.getMetadata().getName());
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Ingress. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
            } catch (final ApiException e) {
                logger.error("Failed to delete Ingress.", e);
            }
        });
    }

    public void deleteIngress(final V1beta1Ingress ingress) throws ApiException {
        final V1ObjectMeta metadata = ingress.getMetadata();
        extensionsV1beta1Api.deleteNamespacedIngress(metadata.getName(), metadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
    }

    public void deleteConfigMap(final V1ConfigMap configMap) throws ApiException {
        final V1ObjectMeta configMapMetadata = configMap.getMetadata();
        coreApi.deleteNamespacedConfigMap(configMapMetadata.getName(), configMapMetadata.getNamespace(), new V1DeleteOptions(), null, null, null, null, null);
    }

    public void deleteStatefulSet(final V1StatefulSet statefulSet) throws ApiException {
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

        final V1ObjectMeta statefulSetMetadata = statefulSet.getMetadata();
        appsApi.deleteNamespacedStatefulSet(statefulSetMetadata.getName(), statefulSetMetadata.getNamespace(), deleteOptions, null, null, null, false, "Foreground");
    }

    public void deleteDeployment(String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        listNamespacedDeployment(namespace, null, labelSelector).forEach(deployment -> {
            try {
                deleteDeployment(deployment);
                logger.debug("Deleted Ingress namespace={} name={}", deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
            } catch (final JsonSyntaxException e) {
                logger.debug("Caught JSON exception while deleting Ingress. Ignoring due to https://github.com/kubernetes-client/java/issues/86.", e);
            } catch (final ApiException e) {
                logger.error("Failed to delete Ingress.", e);
            }
        });
    }

    public void deleteDeployment(final ExtensionsV1beta1Deployment deployment) throws ApiException {
        final V1ObjectMeta metadata = deployment.getMetadata();
        V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        extensionsV1beta1Api.deleteNamespacedDeployment(metadata.getName(), metadata.getNamespace(), deleteOptions, null, null, null, null, "Foreground");
    }

    public void deleteDeployment(final String name, final String namespace) throws ApiException {
        V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        appsApi.deleteNamespacedDeployment(name, namespace, deleteOptions, null, null, null, false, "Foreground");
    }
    
    public void deleteService(final String name, final String namespace) throws ApiException {
        V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");
        coreApi.deleteNamespacedService(name, namespace, deleteOptions, null, null, null, false, "Foreground");
    }
    
    public void deletePersistentVolumeClaim(final V1Pod pod) throws ApiException {
        final V1DeleteOptions deleteOptions = new V1DeleteOptions().propagationPolicy("Foreground");

        // TODO: maybe delete all volumes?
        final String pvcName = pod.getSpec().getVolumes().get(0).getPersistentVolumeClaim().getClaimName();
        final V1PersistentVolumeClaim pvc = coreApi.readNamespacedPersistentVolumeClaim(pvcName, pod.getMetadata().getNamespace(), null, null, null);

        logger.debug("Deleting PVC name={}", pvcName);
        coreApi.deleteNamespacedPersistentVolumeClaim(pvcName, pod.getMetadata().getNamespace(), deleteOptions, null, null, null, null, "Foreground");
    }

    /*
    public void deletePersistentVolumeAndPersistentVolumeClaim(final V1Pod pod) throws ApiException {
        logger.debug("Deleting Pod Persistent Volumes and Claims.");

        final V1DeleteOptions deleteOptions = new V1DeleteOptions()
                .propagationPolicy("Foreground");

        // TODO: maybe delete all volumes?
        final String pvcName = pod.getSpec().getVolumes().get(0).getPersistentVolumeClaim().getClaimName();
        final V1PersistentVolumeClaim pvc = coreApi.readNamespacedPersistentVolumeClaim(pvcName, pod.getMetadata().getNamespace(), null, null, null);

        coreApi.deleteNamespacedPersistentVolumeClaim(pvcName, pod.getMetadata().getNamespace(), deleteOptions, null, null, null, null, null);
        coreApi.deletePersistentVolume(pvc.getSpec().getVolumeName(), deleteOptions, null, null, null, null, null);
    }
    */

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

        final V1PodPage firstPage = new V1PodPage(null);

        return new ResourceListIterable<>(firstPage);
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

        final V1StatefulSetPage firstPage = new V1StatefulSetPage(null);

        return new ResourceListIterable<>(firstPage);
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

        final V1ConfigMapPage firstPage = new V1ConfigMapPage(null);
        return new ResourceListIterable<>(firstPage);
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


    public Iterable<ExtensionsV1beta1Deployment> listNamespacedDeployment(final String namespace, @Nullable final String fieldSelector, @Nullable final String labelSelector) throws ApiException {
        class V1DeploymentPage implements ResourceListIterable.Page<ExtensionsV1beta1Deployment> {
            private final ExtensionsV1beta1DeploymentList deploymentList;

            private V1DeploymentPage(final String continueToken) throws ApiException {
                deploymentList = extensionsV1beta1Api.listNamespacedDeployment(namespace, null, null, continueToken, fieldSelector, labelSelector, null, null, null, null);
            }

            @Override
            public Collection<ExtensionsV1beta1Deployment> items() {
                return deploymentList.getItems();
            }

            @Override
            public ResourceListIterable.Page<ExtensionsV1beta1Deployment> nextPage() throws ApiException {
                final String continueToken = deploymentList.getMetadata().getContinue();

                if (Strings.isNullOrEmpty(continueToken))
                    return null;

                return new V1DeploymentPage(continueToken);
            }
        }

        final V1DeploymentPage firstPage = new V1DeploymentPage(null);
        return new ResourceListIterable<>(firstPage);
    }

    public static class ConfigMapVolumeMount {
        final String name, mountPath;

        final V1ConfigMapVolumeSource volumeSource;

        private ConfigMapVolumeMount(final String name, final String mountPath, final V1ConfigMapVolumeSource volumeSource) {
            this.name = name;
            this.mountPath = mountPath;
            this.volumeSource = volumeSource;
        }
    }

    public DataCenter readDatacenter(final Key key) throws ApiException {
        final Call call = customObjectsApi.getNamespacedCustomObjectCall("stable.strapdata.com", "v1",
                key.getNamespace(), "elassandradatacenters", key.getName(), null, null);
        final ApiResponse<DataCenter> apiResponse = customObjectsApi.getApiClient().execute(call, DataCenter.class);
        return apiResponse.getData();
    }
    
    public void updateDataCenterStatus(final DataCenter dc) throws ApiException {
        customObjectsApi.replaceNamespacedCustomObjectStatus("stable.strapdata.com", "v1",
                dc.getMetadata().getNamespace(), "elassandradatacenters", dc.getMetadata().getName(), dc);
    }
    
    public void updateTaskStatus(Task task) throws ApiException {
        customObjectsApi.replaceNamespacedCustomObjectStatus("stable.strapdata.com", "v1",
                task.getMetadata().getNamespace(), "elassandratasks", task.getMetadata().getName(), task);
    }
    
    public void createTask(Task task) throws ApiException {
        customObjectsApi.createNamespacedCustomObject("stable.strapdata.com", "v1",
                task.getMetadata().getNamespace(), "elassandratasks", task, null);
    }
    
    public void createTask(DataCenter dc, String taskType, Consumer<TaskSpec> modifier) throws ApiException {
            final String name = OperatorNames.generateTaskName(dc, taskType);
            final Task task = Task.fromDataCenter(name, dc);
            modifier.accept(task.getSpec());
            this.createTask(task);
    }

    public void deleteTasks(DataCenter dc) throws ApiException {

    }
}
