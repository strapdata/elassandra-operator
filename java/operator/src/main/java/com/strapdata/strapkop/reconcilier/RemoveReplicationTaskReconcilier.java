package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.BlockReason;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.task.RemoveReplicationTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CustomObjectsApi;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Single;
import org.elasticsearch.common.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
@Infrastructure
public class RemoveReplicationTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(RemoveReplicationTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;

    public RemoveReplicationTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                            final K8sResourceUtils k8sResourceUtils,
                                            final SidecarClientFactory sidecarClientFactory,
                                            final CustomObjectsApi customObjectsApi,
                                            final ApplicationContext context,
                                            final CqlRoleManager cqlRoleManager,
                                            final CqlKeyspaceManager cqlKeyspaceManager,
                                            final MeterRegistry meterRegistry) {
        super(reconcilierObserver, "decommission", k8sResourceUtils, meterRegistry);
        this.sidecarClientFactory = sidecarClientFactory;
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
    }

    public BlockReason blockReason() {
        return BlockReason.DECOMMISSION;
    }

    /**
     * Remove a datacenter from C* replication map
     * @param taskWrapper
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Single<TaskPhase> doTask(TaskWrapper taskWrapper, DataCenter dc) throws Exception {
        final Task task = taskWrapper.getTask();
        final RemoveReplicationTaskSpec removeReplicationTaskSpec = task.getSpec().getRemoveReplication();
        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);

        if (Strings.isNullOrEmpty(removeReplicationTaskSpec.getDcName())) {
            logger.warn("dcName not set, ignore task");
            return Single.just(TaskPhase.FAILED);
        }

        // remove the dc from all replication maps
        return this.cqlKeyspaceManager.removeDcFromReplicationMap(dc, removeReplicationTaskSpec.getDcName(), cqlSessionHandler)
                .andThen(Single.just(TaskPhase.SUCCEED));
    }
}
