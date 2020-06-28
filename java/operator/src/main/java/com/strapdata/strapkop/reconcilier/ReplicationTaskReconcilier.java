/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.reconcilier;

import com.google.common.base.Strings;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.cql.CqlKeyspace;
import com.strapdata.strapkop.cql.CqlKeyspaceManager;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.task.ReplicationTaskSpec;
import com.strapdata.strapkop.model.k8s.task.Task;
import com.strapdata.strapkop.model.k8s.task.TaskPhase;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Pod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Infrastructure;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Update replication map.
 * Flush nodes when adding a DC to get all data when streaming
 */
@Singleton
@Infrastructure
public class ReplicationTaskReconcilier extends TaskReconcilier {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationTaskReconcilier.class);
    private final ApplicationContext context;
    private final CqlRoleManager cqlRoleManager;
    private final CqlKeyspaceManager cqlKeyspaceManager;
    private final JmxmpElassandraProxy jmxmpElassandraProxy;

    public ReplicationTaskReconcilier(ReconcilierObserver reconcilierObserver,
                                      final OperatorConfig operatorConfig,
                                      final K8sResourceUtils k8sResourceUtils,
                                      final CustomObjectsApi customObjectsApi,
                                      final JmxmpElassandraProxy jmxmpElassandraProxy,
                                      final ApplicationContext context,
                                      final CqlRoleManager cqlRoleManager,
                                      final CqlKeyspaceManager cqlKeyspaceManager,
                                      final MeterRegistry meterRegistry,
                                      final DataCenterReconcilier dataCenterController,
                                      final SharedInformerFactory sharedInformerFactory,
                                      final DataCenterStatusCache dataCenterStatusCache,
                                      ExecutorFactory executorFactory,
                                      @Named("tasks") UserExecutorConfiguration userExecutorConfiguration) {
        super(reconcilierObserver, operatorConfig, k8sResourceUtils, meterRegistry,
                dataCenterController, sharedInformerFactory, dataCenterStatusCache, executorFactory, userExecutorConfiguration);
        this.context = context;
        this.cqlRoleManager = cqlRoleManager;
        this.cqlKeyspaceManager = cqlKeyspaceManager;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    /**
     * Remove a datacenter from C* replication map
     *
     * @param task
     * @param dc
     * @return
     * @throws ApiException
     */
    @Override
    protected Completable doTask(final DataCenter dc, final DataCenterStatus dataCenterStatus, final Task task, Iterable<V1Pod> pods) throws Exception {
        final ReplicationTaskSpec replicationTaskSpec = task.getSpec().getReplication();

        if (Strings.isNullOrEmpty(replicationTaskSpec.getDcName())) {
            logger.error("datacenter={} task={} dcName not set, ignoring task", dc.id(), task.id());
            return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED,
                    replicationTaskSpec.getAction().equals(ReplicationTaskSpec.Action.ADD) ? "replicationAdd" : "replicationRemove");
        }

        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
        switch (replicationTaskSpec.getAction()) {
            case ADD: {
                final Map<String, Integer> replicationMap = new HashMap<>();
                replicationMap.putAll(replicationTaskSpec.getReplicationMap());
                for (CqlKeyspace systemKs : CqlKeyspaceManager.SYSTEM_KEYSPACES)
                    replicationMap.putIfAbsent(systemKs.getName(), systemKs.getRf());

                // add replication for these keyspaces
                Completable todo = Completable.complete();
                for (Map.Entry<String, Integer> entry : replicationMap.entrySet()) {
                    todo = todo.andThen(this.cqlKeyspaceManager.updateKeyspaceReplicationMap(dc, dataCenterStatus, replicationTaskSpec.getDcName(), entry.getKey(), Math.min(entry.getValue(), replicationTaskSpec.getDcSize()), cqlSessionHandler, false));
                }

                // flush sstables in parallel to stream properly
                List<CompletableSource> fulshCompletables = new ArrayList<>();
                for (V1Pod v1Pod : pods) {
                    fulshCompletables.add(jmxmpElassandraProxy.flush(ElassandraPod.fromV1Pod(v1Pod), null)
                            .toSingleDefault(task)
                            .map(t -> {
                                // update pod status in memory (no etcd update)
                                task.getStatus().getPods().put(v1Pod.getMetadata().getName(), TaskPhase.SUCCEED);
                                return t;
                            })
                            .ignoreElement()
                    );
                }
                return todo.andThen(Completable.mergeArray(fulshCompletables.toArray(new CompletableSource[fulshCompletables.size()]))
                                .toSingleDefault(TaskPhase.SUCCEED)
                                .flatMapCompletable(phase -> finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "replicationAdd"))
                                .onErrorResumeNext(throwable -> {
                                    logger.error("datacenter={} task={} add replication failed, error={}",
                                            dc.id(), task.id(), replicationTaskSpec.getDcName(), throwable.getMessage());
                                    task.getStatus().setLastMessage(throwable.getMessage());
                                    return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.FAILED, "replicationAdd");
                                }))
                        .doFinally(() -> cqlSessionHandler.close());
            }
            case REMOVE: {
                return this.cqlKeyspaceManager.removeDcFromReplicationMap(dc, dataCenterStatus, replicationTaskSpec.getDcName(), cqlSessionHandler)
                        .toSingleDefault(TaskPhase.SUCCEED)
                        .flatMapCompletable(phase -> finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.SUCCEED, "replicationRemove"))
                        .onErrorResumeNext(throwable -> {
                            logger.error("datacenter={} task={} remove replication failed, error={}",
                                    dc.id(), task.id(), replicationTaskSpec.getDcName(), throwable.getMessage());
                            task.getStatus().setLastMessage(throwable.getMessage());
                            return finalizeTaskStatus(dc, dataCenterStatus, task, TaskPhase.FAILED, "replicationRemove");
                        })
                        .doFinally(() -> cqlSessionHandler.close());
            }
        }
        cqlSessionHandler.close(); // close on error
        throw new IllegalArgumentException("Unknwon action");
    }

    @Override
    public Single<List<V1Pod>> init(Task task, DataCenter dc) {
        return listAllDcPods(task, dc).map(pods -> initTaskStatusPodMap(task, pods));
    }
}
