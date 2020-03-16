package com.strapdata.strapkop.reconcilier;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.event.Pod;
import com.strapdata.strapkop.handler.ElassandraPodHandler;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.RemoveNodesTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.kubernetes.client.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.concurrent.Callable;

@Singleton
@Infrastructure
public class RemoveNodesTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RemoveNodesTaskReconcilier.class);
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public RemoveNodesTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                      final K8sResourceUtils k8sResourceUtils,
                                      final JmxmpElassandraProxy jmxmpElassandraProxy,
                                      final CustomObjectsApi customObjectsApi,
                                      final ApplicationContext context,
                                      final CqlRoleManager cqlRoleManager,
                                      final CqlKeyspaceManager cqlKeyspaceManager,
                                      final DataCenterController dataCenterController,
                                      final DataCenterCache dataCenterCache,
                                      final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "removeNodes", k8sResourceUtils, meterRegistry, dataCenterController, dataCenterCache);
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    public BlockReason blockReason() {
        return BlockReason.REMOVE_NODES;
    }

    /**
     * Remove node of a stopped datacenters.
     *
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(final Task task, final DataCenter dc) throws Exception {
        final RemoveNodesTaskSpec removeNodesTaskSpec = task.getSpec().getRemoveNodes();
        final String dcName = removeNodesTaskSpec.getDcName();

        if (Strings.isNullOrEmpty(dcName)) {
            logger.warn("datacenter={} removeNodes={} dcName not set, ignoring task={}", dc.id(), task.id());
            return Single.just(TaskPhase.FAILED);
        }

        // remove nodes from the cluster
        // search a ready pod
        final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(OperatorLabels.PARENT, dc.getMetadata().getName()));
        return Single.fromCallable(new Callable<ElassandraPod>() {
            @Override
            public ElassandraPod call() throws Exception {
                Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), labelSelector, labelSelector);
                for (V1Pod v1Pod : pods) {
                    Pod pod = new Pod(v1Pod, ElassandraPodHandler.CONTAINER_NAME);
                    if (pod.isReady()) {
                        return new ElassandraPod(dc.getMetadata().getNamespace(),
                                dc.getSpec().getClusterName(),
                                dc.getSpec().getDatacenterName(),
                                v1Pod.getMetadata().getName());
                    }
                }
                throw new IllegalStateException("No elassandra pod ready");
            }
        }).flatMap(elassandraPod -> jmxmpElassandraProxy.removeDcNodes(elassandraPod, dcName).toSingleDefault(TaskPhase.SUCCEED))
        .onErrorResumeNext(throwable -> {
            logger.error("datacenter={} task={} Error removing nodes of dc={} error:{}",
                    dc.id(), task.id(), dcName, throwable.getMessage());
            task.getStatus().setLastMessage(throwable.getMessage());
            return Single.just(TaskPhase.FAILED);
        });
    }

    @Override
    public Completable initializePodMap(Task task, DataCenter dc) {
        return Completable.complete();
    }
}
