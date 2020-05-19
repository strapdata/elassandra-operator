package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.BackupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.List;

@Singleton
@Infrastructure
public class BackupTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(BackupTaskReconcilier.class);
    private final SidecarClientFactory sidecarClientFactory;
    private final CqlRoleManager cqlRoleManager;

    public BackupTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                 final OperatorConfig operatorConfig,
                                 final K8sResourceUtils k8sResourceUtils,
                                 final SidecarClientFactory sidecarClientFactory,
                                 final MeterRegistry meterRegistry,
                                 final DataCenterController dataCenterController,
                                 final DataCenterCache dataCenterCache,
                                 final CqlRoleManager cqlRoleManager,
                                 ExecutorFactory executorFactory,
                                 @Named("tasks") UserExecutorConfiguration userExecutorConfiguration ) {
        super(reconcilierObserver, "backup", operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, dataCenterCache, executorFactory, userExecutorConfiguration);
        this.sidecarClientFactory = sidecarClientFactory;
        this.cqlRoleManager = cqlRoleManager;
    }

    /**
     * Execute backup concurrently on all nodes
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws ApiException {
        // TODO: better backup with sstableloader and progress tracking
        // right now it just call the backup api on every nodes sidecar in parallel
        final BackupTaskSpec backupSpec = task.getSpec().getBackup();

        return Observable.fromIterable(pods)
                .subscribeOn(Schedulers.io())
                .flatMapSingle(pod -> {

                    return sidecarClientFactory.clientForPod(ElassandraPod.fromV1Pod(pod), cqlRoleManager.get(dc, CqlRole.STRAPKOP_ROLE.getUsername()))
                            .snapshot(backupSpec.getRepository(), backupSpec.getKeyspaces())
                            .map(backupResponse -> {
                                logger.debug("Received backupSpec response with status = {}", backupResponse.getStatus());
                                boolean success = backupResponse.getStatus().equalsIgnoreCase("success");
                                if (!success)
                                    task.getStatus().setLastMessage("Basckup task="+task.getMetadata().getName()+" on pod="+pod+" failed");
                                return new Tuple2<String, Boolean>(pod.getMetadata().getName(), success);
                            });
                })
                .toList()
                .flatMapCompletable(list -> finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED));
    }



    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }
}
