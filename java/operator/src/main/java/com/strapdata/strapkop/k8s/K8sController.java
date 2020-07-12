package com.strapdata.strapkop.k8s;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.cache.StatefulsetCache;
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
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
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
    TaskQueues taskQueues;

    @Inject
    SharedInformerFactory sharedInformerFactory;

    @Inject
    DataCenterReconcilier dataCenterReconcilier;

    @Inject
    DataCenterStatusCache dataCenterStatusCache;

    @Inject
    StatefulsetCache statefulsetCache;

    @Inject
    TaskResolver taskReconcilierResolver;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    public void start() {
        addNodeInformer();
        addPodInformer();
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
                                    null,   // TODO: zone/node filter ?
                                    null,
                                    params.resourceVersion,
                                    params.timeoutSeconds,
                                    params.watch,
                                    null);
                        },
                        V1Node.class,
                        V1NodeList.class);
    }

    void addPodInformer() {
        SharedIndexInformer<V1Pod> podInformer =
                sharedInformerFactory.sharedIndexInformerFor(
                        (CallGeneratorParams params) -> coreV1Api.listPodForAllNamespacesCall(
                                null,
                                null,
                                null,
                                OperatorLabels.toSelector(OperatorLabels.MANAGED), // TODO: watch only pods having rack index=0 for seeds ?
                                null,
                                null,
                                params.resourceVersion,
                                params.timeoutSeconds,
                                params.watch,
                                null),
                        V1Pod.class,
                        V1PodList.class);
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
                        .withCompletable(dataCenterReconcilier.initDatacenter(dataCenter, new Operation()
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
                            .withCompletable(dataCenterReconcilier.updateDatacenter(
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
                        .withCompletable(dataCenterReconcilier.deleteDatacenter(dc)
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
        if (dataCenter == null) {
            logger.warn("datacenter={}/{} sts={} replicas={}/{} datacenter CRD not found, ignoring event",
                    namespace, parent,
                    sts.getMetadata().getName(),
                    sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas());
            return;
        }

        logger.debug("datacenter={}/{} sts={} replicas={}/{}, triggering a dc statefulSetStatusUpdate",
                namespace, parent,
                sts.getMetadata().getName(),
                sts.getStatus().getReadyReplicas(), sts.getStatus().getReplicas());

        workQueues.submit(new Reconciliation(sts.getMetadata(), Reconciliation.Kind.STATEFULSET, Reconciliation.Type.MODIFIED)
                .withKey(key)
                .withCompletable(dataCenterReconcilier.statefulsetStatusUpdate(dataCenter,
                        new Operation()
                                .withLastTransitionTime(new Date())
                                .withTriggeredBy("Status update statefulset=" + sts.getMetadata().getName() + " replicas=" +
                                        sts.getStatus().getReadyReplicas() + "/" + sts.getStatus().getReplicas()),
                        sts)
                        .onErrorComplete(t -> {
                            if (t instanceof NoSuchElementException) {
                                return true;
                            }
                            logger.warn("datacenter={}/{} statefulSetUpdate failed: {}", parent, namespace, t.toString());
                            return false;
                        })));
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
                if (task.getMetadata().getGeneration() > oldGeneration) {
                    reconcileTask(task, Reconciliation.Type.MODIFIED, managed);
                }
            }

            @Override
            public void onDelete(Task task, boolean deletedFinalStateUnknown) {
                logger.debug("task={}", task.id());
                // TODO: cancel removed tasks
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed.decrementAndGet();
            }
        }, 15000);
    }

    public void reconcileTask(Task task, Reconciliation.Type type, AtomicInteger managed) {
        final Key dcKey = new Key(task.getMetadata().getNamespace(), OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()));
        final TaskStatus taskStatus = task.getStatus();
        logger.debug("task={} generation={} taskStatus={}", task.id(), task.getMetadata().getGeneration(), taskStatus);
        if (taskStatus.getPhase() == null || !taskStatus.getPhase().isTerminated()) {
            taskQueues.submit(new Reconciliation(task.getMetadata(), Reconciliation.Kind.TASK, type)
                    .withKey(dcKey)
                    .withCompletable(taskReconcilierResolver.getTaskReconcilier(task).reconcile(task)));
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
                        .withCompletable(dataCenterReconcilier.deploymentAvailable(dataCenter, op, deployment)));
            }
        }
    }
}
