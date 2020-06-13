package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import com.strapdata.strapkop.reconcilier.TaskResolver;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class TaskHandler extends TerminalHandler<K8sWatchEvent<Task>> {

    private final Logger logger = LoggerFactory.getLogger(TaskHandler.class);

    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);

    private final WorkQueues workQueues;
    private final OperatorConfig operatorConfig;
    private final K8sResourceUtils k8sResourceUtils;
    private final TaskResolver taskReconcilierResolver;
    private final MeterRegistry meterRegistry;

    AtomicInteger managed;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "task"));

    ConcurrentMap<Tuple2<Key, String>, Disposable> notTerminatedTasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void initGauge() {
        managed = meterRegistry.gauge("k8s.managed", tags, new AtomicInteger(0));
    }

    public TaskHandler(WorkQueues workQueues,
                       OperatorConfig operatorConfig,
                       MeterRegistry meterRegistry,
                       final K8sResourceUtils k8sResourceUtils,
                       final TaskResolver taskReconcilierResolver) {
        this.meterRegistry = meterRegistry;
        this.workQueues = workQueues;
        this.operatorConfig = operatorConfig;
        this.k8sResourceUtils = k8sResourceUtils;
        this.taskReconcilierResolver = taskReconcilierResolver;
    }

    @Override
    public void accept(K8sWatchEvent<Task> event) throws Exception {
        Task task;
        logger.trace("Task event={}", event);
        switch (event.getType()) {
            case INITIAL:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.init", tags).increment();
                managed.incrementAndGet();
                reconcileTask(event.getResource(), event.getType());
                break;

            case ADDED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed.incrementAndGet();
                reconcileTask(event.getResource(), event.getType());
                break;

            case MODIFIED:
                logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                meterRegistry.counter("k8s.event.modified", tags).increment();
                Long generation = event.getResource().getStatus().getObservedGeneration();
                if (generation == null || event.getResource().getMetadata().getGeneration() > generation) {
                    reconcileTask(event.getResource(), event.getType());
                }
                break;

            case DELETED: {
                    logger.debug("event type={} metadata={}", event.getType(), event.getResource().getMetadata().getName());
                    task = event.getResource();
                    final Tuple2<Key, String> key = new Tuple2<>(
                            new Key(OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()), task.getMetadata().getNamespace()),
                            task.getMetadata().getName()
                    );
                    Disposable disposable = notTerminatedTasks.get(key);
                    if (disposable != null) {
                        logger.debug("task={} cancelled", task.id());
                        meterRegistry.counter("k8s.event.cancelled", tags).increment();
                        disposable.dispose();
                    }
                    meterRegistry.counter("k8s.event.deleted", tags).increment();
                    managed.decrementAndGet();
                }
                break;

            case ERROR:
                logger.warn("event type={}", event.getType());
                meterRegistry.counter("k8s.event.error", tags).increment();
                break;
        }
    }

    private void handleWrongTaskType(K8sWatchEvent<Task> data) {
        logger.error("wrong task arguments for {}, no task reconcilier found", data.getResource().getMetadata().getName());
        // TODO: write message in task status
    }

    public void reconcileTask(Task task, K8sWatchEvent.Type eventType) throws Exception {
        final ClusterKey clusterKey = new ClusterKey(
                task.getSpec().getCluster(),
                task.getMetadata().getNamespace()
        );

        TaskStatus taskStatus = task.getStatus();
        logger.debug("task={} generation={} taskStatus={}", task.id(), task.getMetadata().getGeneration(), taskStatus);
        if (taskStatus.getPhase() == null || !taskStatus.getPhase().isTerminated()) {
            // execute task
            final Tuple2<Key, String> key = new Tuple2<>(
                    new Key(OperatorNames.dataCenterResource(task.getSpec().getCluster(), task.getSpec().getDatacenter()), task.getMetadata().getNamespace()),
                    task.getMetadata().getName()
            );
            Completable completable = taskReconcilierResolver.getTaskReconcilier(task).reconcile(task);
            // keep a task ref to cancel it on delete
            completable.doFinally(() -> notTerminatedTasks.remove(key));
            notTerminatedTasks.put(key, completable.subscribe());
            workQueues.submit(clusterKey, task.getMetadata().getResourceVersion(), Reconciliable.Kind.TASK, eventType, completable);
        } else {
            // purge old task.
            org.joda.time.DateTime creation = task.getMetadata().getCreationTimestamp();
            long retentionInstant = System.currentTimeMillis() - operatorConfig.getTaskRetention().getSeconds() * 1000;
            if (creation.isBefore(retentionInstant)) {
                logger.info("Delete old terminated task={}", task.id());
                Completable delete = k8sResourceUtils.deleteTask(task.getMetadata()).ignoreElement();
                workQueues.submit(clusterKey, task.getMetadata().getResourceVersion(), Reconciliable.Kind.TASK, DELETED, delete);
            }
        }
    }
}
