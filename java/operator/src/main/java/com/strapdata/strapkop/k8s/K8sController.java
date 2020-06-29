package com.strapdata.strapkop.k8s;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.cache.StatefulsetCache;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.StrapdataCrdGroup;
import com.strapdata.strapkop.model.k8s.datacenter.*;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskList;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.reconcilier.DataCenterReconcilier;
import com.strapdata.strapkop.reconcilier.Reconciliation;
import com.strapdata.strapkop.reconcilier.TaskResolver;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.CallGeneratorParams;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.discovery.event.ServiceShutdownEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;
import io.vavr.Tuple2;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
@Infrastructure
public class K8sController {

    private final Logger logger = LoggerFactory.getLogger(K8sController.class);

    @Inject
    CoreV1Api coreV1Api;

    @Inject
    AppsV1Api appsApi;

    @Inject
    CustomObjectsApi customObjectsApi;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    WorkQueues workQueues;

    @Inject
    SharedInformerFactory sharedInformerFactory;

    @Inject
    DataCenterReconcilier dataCenterController;

    @Inject
    DataCenterStatusCache dataCenterStatusCache;

    @Inject
    StatefulsetCache statefulsetCache;

    @Inject
    TaskResolver taskReconcilierResolver;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    ConcurrentMap<Tuple2<Key, String>, Disposable> ongoingTasks = new ConcurrentHashMap<>();

    public void start() {
        addNodeInformer();
        addServiceAccountInformer();
        addStatefulSetInformer();
        addDeploymentInformer();
        addDataCenterInformer();
        addTaskInformer();

        sharedInformerFactory.startAllRegisteredInformers();
        logger.info("Kubernetes informer factory started");
    }

    @EventListener
    @Async
    void onShutdown(ServiceShutdownEvent event) {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    void addNodeInformer() {
        SharedIndexInformer<V1Node> nodeInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> {
                            return coreV1Api.listNodeCall(
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    params.resourceVersion,
                                    params.timeoutSeconds,
                                    params.watch,
                                    null);
                        },
                        V1Node.class,
                        V1NodeList.class);
    }

    void addServiceAccountInformer() {
        SharedIndexInformer<V1ServiceAccount> saInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> coreV1Api.listServiceAccountForAllNamespacesCall(
                                null,
                                null,
                                null,
                                OperatorLabels.toSelector(OperatorLabels.MANAGED),
                                null,
                                null,
                                params.resourceVersion,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1ServiceAccount.class,
                        V1ServiceAccountList.class);
    }

    void addDataCenterInformer() {
        SharedIndexInformer<DataCenter> dcInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> {
                            return customObjectsApi.listClusterCustomObjectCall(
                                    StrapdataCrdGroup.GROUP,
                                    DataCenter.VERSION,
                                    DataCenter.PLURAL,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    params.resourceVersion,
                                    params.timeoutSeconds,
                                    params.watch, null);
                        },
                        DataCenter.class,
                        DataCenterList.class,
                        5000);
        dcInformer.addEventHandlerWithResyncPeriod(new ResourceEventHandler<DataCenter>() {
            AtomicInteger managed = new AtomicInteger(0);
            List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "datacenter"));

            @Override
            public void onAdd(DataCenter dataCenter) {
                logger.debug("dc={} status.rackStatus={}",
                        dataCenter.id(), dataCenter.getStatus().getRackStatuses());
                workQueues.submit(new Reconciliation(dataCenter.getMetadata(), Reconciliation.Kind.DATACENTER, Reconciliation.Type.ADDED)
                            .withKey(new Key(dataCenter.getMetadata()))
                            .withCompletable(dataCenterController.initDatacenter(dataCenter, new Operation()
                                    .withLastTransitionTime(new Date())
                                    .withTriggeredBy("Datacenter added"))
                                    .doOnComplete(() -> {
                                        managed.incrementAndGet();
                                        meterRegistry.counter("k8s.event.add", tags).increment();
                                    })));
            }

            @Override
            public void onUpdate(DataCenter oldObj, DataCenter newObj) {
                if (oldObj.getMetadata().getGeneration() != newObj.getMetadata().getGeneration()) {
                    logger.debug("dc={} generation={}", oldObj.id(), newObj.getMetadata().getGeneration());
                    workQueues.submit(new Reconciliation(newObj.getMetadata(), Reconciliation.Kind.DATACENTER, Reconciliation.Type.MODIFIED)
                            .withKey(new Key(newObj.getMetadata()))
                            .withCompletable(dataCenterController.updateDatacenter(
                                    newObj,
                                    new Operation()
                                            .withLastTransitionTime(new Date())
                                            .withTriggeredBy("Datacenter modified spec generation=" + newObj.getMetadata().getGeneration()))
                                    .doFinally(() -> meterRegistry.counter("k8s.event.modified", tags).increment())));
                }
            }

            @Override
            public void onDelete(DataCenter dc, boolean deletedFinalStateUnknown) {
                logger.debug("dc={}", dc.id());
                workQueues.submit(new Reconciliation(dc.getMetadata(), Reconciliation.Kind.DATACENTER, Reconciliation.Type.DELETED)
                        .withKey(new Key(dc.getMetadata()))
                        .withCompletable(dataCenterController.deleteDatacenter(dc)
                                .doOnComplete(() -> {
                                    managed.decrementAndGet();
                                    meterRegistry.counter("k8s.event.deleted", tags).increment();
                                    workQueues.remove(new Key(dc.getMetadata()));
                                })));
            }
        }, 5000);
    }

    void addStatefulSetInformer() {
        SharedIndexInformer<V1StatefulSet> stsInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> appsApi.listStatefulSetForAllNamespacesCall(
                                null,
                                null,
                                null,
                                OperatorLabels.toSelector(OperatorLabels.MANAGED),
                                null,
                                null,
                                params.resourceVersion,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1StatefulSet.class,
                        V1StatefulSetList.class);

        stsInformer.addEventHandlerWithResyncPeriod(new ResourceEventHandler<V1StatefulSet>() {
            AtomicInteger managed = new AtomicInteger(0);
            List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "statefulset"));

            @Override
            public void onAdd(V1StatefulSet sts) {
                logger.debug("sts={}/{}", sts.getMetadata().getNamespace(), sts.getMetadata().getName());
                statefulsetCache.update(sts);
                meterRegistry.counter("k8s.event.add", tags).increment();
                managed.incrementAndGet();
                reconcileSts(sts);
            }

            @Override
            public void onUpdate(V1StatefulSet oldObj, V1StatefulSet sts) {
                logger.debug("sts={}/{}", sts.getMetadata().getNamespace(), sts.getMetadata().getName());
                statefulsetCache.update(sts);
                meterRegistry.counter("k8s.event.modifed", tags).increment();
                managed.incrementAndGet();
                reconcileSts(sts);
            }

            @Override
            public void onDelete(V1StatefulSet sts, boolean deletedFinalStateUnknown) {
                logger.debug("sts={}/{}", sts.getMetadata().getNamespace(), sts.getMetadata().getName());
                Key key = new Key(sts.getMetadata());
                statefulsetCache.remove(key);
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
            }
        }, 15000);
    }

    public void reconcileSts(V1StatefulSet sts) {
        final String parent = sts.getMetadata().getLabels().get(OperatorLabels.PARENT);
        final String namespace = sts.getMetadata().getNamespace();
        final Key key = new Key(namespace, parent);

        DataCenter dataCenter = sharedInformerFactory.getExistingSharedIndexInformer(DataCenter.class).getIndexer().getByKey(namespace + "/" + parent);
        if (dataCenter != null) {
            DataCenterStatus dataCenterStatus = dataCenterStatusCache.getOrDefault(key, dataCenter.getStatus());
            RackStatus rackStatus = dataCenterStatus.getRackStatuses().get(Integer.parseInt(sts.getMetadata().getLabels().get(OperatorLabels.RACKINDEX)));
            if (rackStatus == null || ObjectUtils.defaultIfNull(rackStatus.getReadyReplicas(), 0) != ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0)) {
                logger.info("datacenter={}/{} sts={} replicas={}/{}, triggering a dc statefulSetStatusUpdate",
                        namespace, parent,
                        sts.getMetadata().getName(),
                        sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas());
                Operation op = new Operation()
                        .withLastTransitionTime(new Date())
                        .withTriggeredBy("Status update statefulset=" + sts.getMetadata().getName() + " replicas=" +
                                sts.getStatus().getReadyReplicas() + "/" + sts.getStatus().getReplicas());
                workQueues.submit(new Reconciliation(sts.getMetadata(), Reconciliation.Kind.STATEFULSET, Reconciliation.Type.MODIFIED)
                        .withKey(key)
                        .withCompletable(dataCenterController.statefulsetStatusUpdate(dataCenter, op, sts)
                                .onErrorComplete(t -> {
                                    if (t instanceof NoSuchElementException) {
                                        return true;
                                    }
                                    logger.warn("datacenter={}/{} statefulSetUpdate failed: {}", parent, namespace, t.toString());
                                    return false;
                                })));
            } else {
                logger.warn("datacenter={}/{} sts={} replicas={}/{} NOOP not ready rackStatus={}",
                        namespace, parent,
                        sts.getMetadata().getName(),
                        sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas(), rackStatus);
            }
        } else {
                logger.warn("datacenter={}/{} sts={} replicas={}/{} NOOP datacenter CRD not found",
                        namespace, parent,
                        sts.getMetadata().getName(),
                        sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas());
        }
    }

    void addTaskInformer() {
        SharedIndexInformer<Task> taskInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> customObjectsApi.listClusterCustomObjectCall(
                                StrapdataCrdGroup.GROUP,
                                Task.VERSION,
                                Task.PLURAL,
                                null,
                                null,
                                null,
                                null,
                                null,
                                params.resourceVersion, params.timeoutSeconds, params.watch, null),
                        Task.class,
                        TaskList.class);
        taskInformer.addEventHandlerWithResyncPeriod(new ResourceEventHandler<Task>() {

            AtomicInteger managed = new AtomicInteger(0);
            List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "task"));

            @Override
            public void onAdd(Task task) {
                logger.debug("task={}", task.id());
                meterRegistry.counter("k8s.event.added", tags).increment();
                reconcileTask(task, Reconciliation.Type.ADDED, managed);
            }

            @Override
            public void onUpdate(Task oldTask, Task task) {
                logger.debug("task={}", task.id());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                Long oldGeneration = oldTask.getMetadata().getGeneration();
                if ( task.getMetadata().getGeneration() > oldGeneration) {
                    reconcileTask(task, Reconciliation.Type.MODIFIED, managed);
                }
            }

            @Override
            public void onDelete(Task task, boolean deletedFinalStateUnknown) {
                logger.debug("task={}", task.id());
                meterRegistry.counter("k8s.event.delete", tags).increment();
                final Tuple2<Key, String> key = new Tuple2<>(
                        new Key(task.getMetadata().getNamespace(), OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter())),
                        task.getMetadata().getName()
                );
                Disposable disposable = ongoingTasks.get(key);
                if (disposable != null) {
                    logger.debug("task={} cancelled", task.id());
                    meterRegistry.counter("k8s.event.cancelled", tags).increment();
                    disposable.dispose();
                }
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
            }
        }, 15000);
    }

    public void reconcileTask(Task task, Reconciliation.Type type, AtomicInteger managed) {
        final ClusterKey clusterKey = new ClusterKey(task.getMetadata().getNamespace(), task.getSpec().getCluster());
        final TaskStatus taskStatus = task.getStatus();
        logger.debug("task={} generation={} taskStatus={}", task.id(), task.getMetadata().getGeneration(), taskStatus);
        if (taskStatus.getPhase() == null || !taskStatus.getPhase().isTerminated()) {
            // execute task
            final Tuple2<Key, String> key = new Tuple2<>(
                    new Key(task.getMetadata().getNamespace(), OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter())),
                    task.getMetadata().getName()
            );
            Completable completable = taskReconcilierResolver.getTaskReconcilier(task).reconcile(task);
            // keep a task ref to cancel it on delete
            completable.doFinally(() -> {
                ongoingTasks.remove(key);
                managed.decrementAndGet();
            });
            ongoingTasks.put(key, completable.subscribe());
            workQueues.submit(new Reconciliation(task.getMetadata(), Reconciliation.Kind.TASK, type)
                    .withKey(clusterKey)
                    .withCompletable(completable));
        }
    }

    void addDeploymentInformer() {
        SharedIndexInformer<V1Deployment> deploymentInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> appsApi.listDeploymentForAllNamespacesCall(
                                null,
                                null,
                                null,
                                OperatorLabels.toSelector(OperatorLabels.MANAGED),
                                null,
                                null,
                                params.resourceVersion,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1Deployment.class,
                        V1DeploymentList.class);
        deploymentInformer.addEventHandlerWithResyncPeriod(new ResourceEventHandler<V1Deployment>() {
            AtomicInteger managed = new AtomicInteger(0);
            List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "task"));

            @Override
            public void onAdd(V1Deployment deployment) {
                logger.debug("deployment={}/{}", deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed.incrementAndGet();
            }

            @Override
            public void onUpdate(V1Deployment oldObj, V1Deployment deployment) {
                logger.debug("deployment={}/{}", deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                reconcileDeployment(deployment);
            }

            @Override
            public void onDelete(V1Deployment deployment, boolean deletedFinalStateUnknown) {
                logger.debug("deployment={}/{}", deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
            }
        }, 30000);
    }

    // trigger a dc reconciliation when plugin deployments become available (allow reaper to register)
    public void reconcileDeployment(V1Deployment deployment) {
        if (ObjectUtils.defaultIfNull(deployment.getStatus().getAvailableReplicas(), 0) > 0) {
            final String parent = deployment.getMetadata().getLabels().get(OperatorLabels.PARENT);
            final String namespace = deployment.getMetadata().getNamespace();
            final Key key = new Key(namespace, parent);
            DataCenter dataCenter = this.sharedInformerFactory.getExistingSharedIndexInformer(DataCenter.class).getIndexer().getByKey(namespace + "/" + parent);
            if (dataCenter != null) {
                logger.info("datacenter={}/{} deployment={}/{} is available, triggering a dc deploymentAvailable",
                        dataCenter.id(), deployment.getMetadata().getName(), deployment.getMetadata().getNamespace());
                Operation op = new Operation()
                        .withLastTransitionTime(new Date())
                        .withTriggeredBy("Status update deployment=" + deployment.getMetadata().getName());
                workQueues.submit(new Reconciliation(deployment.getMetadata(), Reconciliation.Kind.DEPLOYMENT, Reconciliation.Type.MODIFIED)
                        .withKey(key)
                        .withCompletable(dataCenterController.deploymentAvailable(dataCenter, op, deployment)));
            }
        }
    }
}
