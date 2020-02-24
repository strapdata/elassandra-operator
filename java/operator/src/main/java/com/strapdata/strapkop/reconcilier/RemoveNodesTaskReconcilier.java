package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.RemoveNodesTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Optional;

@Singleton
@Infrastructure
public class RemoveNodesTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RemoveNodesTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;

    public RemoveNodesTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                      final K8sResourceUtils k8sResourceUtils,
                                      final SidecarClientFactory sidecarClientFactory,
                                      final CustomObjectsApi customObjectsApi,
                                      final ApplicationContext context,
                                      final CqlRoleManager cqlRoleManager,
                                      final CqlKeyspaceManager cqlKeyspaceManager,
                                      final ElassandraNodeStatusCache elassandraNodeStatusCache,
                                      final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "removeNodes", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
    }

    public BlockReason blockReason() {
        return BlockReason.REMOVE_NODES;
    }

    /**
     * Remove node of a stopped datacenters.
     *
     * @param taskWrapper
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception {
        final Task task = taskWrapper.getTask();
        final RemoveNodesTaskSpec removeNodesTaskSpec = task.getSpec().getRemoveNodes();
        final String removedDc = removeNodesTaskSpec.getDcName();
        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
        final String dcName = removeNodesTaskSpec.getDcName();

        if (Strings.isNullOrEmpty(removeNodesTaskSpec.getDcName())) {
            logger.warn("dcName not set, ignoring task={}", task.getMetadata().getName());
            return Single.just(TaskPhase.FAILED);
        }

        // remove the dc from all replication maps
        Completable todo = this.cqlKeyspaceManager.removeDcFromReplicationMap(dc, removeNodesTaskSpec.getDcName(), cqlSessionHandler);

        // remove nodes from the cluster
        Optional<ElassandraPod> optionalPod = elassandraNodeStatusCache.findPodByStatus(ElassandraNodeStatus.NORMAL);
        if (optionalPod.isPresent()) {
            return todo.andThen(sidecarClientFactory.clientForPod(optionalPod.get()).remove(dcName))
                    .andThen(finalizeTaskStatus(dc, taskWrapper, TaskPhase.SUCCEED))
                    .onErrorResumeNext(throwable -> {
                        logger.error("Error while executing task={} remove nodes in dc={} on pod={} error:{}", task.getMetadata().getName(), dcName, optionalPod.get(), throwable.getMessage());
                        task.getStatus().setLastMessage(throwable.getMessage());
                        return updateTaskStatus(dc, taskWrapper, TaskPhase.FAILED).toSingleDefault(TaskPhase.FAILED);
                    });
        } else {
            logger.warn("task={}, no NORMAL pod found in dc={} to remove dc={}", task.getMetadata().getName(), dc.getMetadata().getName(), dcName);
            return Single.just(TaskPhase.FAILED);
        }
    }
}
