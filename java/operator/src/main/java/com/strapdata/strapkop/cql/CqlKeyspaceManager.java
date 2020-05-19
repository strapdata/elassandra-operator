package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.CqlStatus;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.kubernetes.client.models.V1Pod;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manage keyspace creation, adjust the replication factor for some <b>existing</b> keyspaces, and trigger repairs/cleanups accordingly.
 * Keyspace reconciliation must be made before role reconciliation.
 */
@Infrastructure
public class CqlKeyspaceManager extends AbstractManager<CqlKeyspace> {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    public static final Set<CqlKeyspace> SYSTEM_KEYSPACES = ImmutableSet.of(
            new CqlKeyspace().withName("system_auth").withRf(3).withRepair(true),
            new CqlKeyspace().withName("system_distributed").withRf(3).withRepair(false),
            new CqlKeyspace().withName("system_traces").withRf(3).withRepair(false));

    final K8sResourceUtils k8sResourceUtils;
    final JmxmpElassandraProxy jmxmpElassandraProxy;

    public CqlKeyspaceManager(final K8sResourceUtils k8sResourceUtils, final JmxmpElassandraProxy jmxmpElassandraProxy) {
        super();
        this.k8sResourceUtils = k8sResourceUtils;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
    }

    private String elasticAdminKeyspaceName(DataCenter dataCenter) {
        return (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
    }

    /**
     * Create and adjust keyspace RF
     *
     * @param dataCenter
     * @return
     * @throws StrapkopException
     */
    public Single<Boolean> reconcileKeyspaces(final DataCenter dataCenter, Boolean updateStatus, final CqlSessionSupplier sessionSupplier, PluginRegistry pluginRegistry) {
        return Single.just(updateStatus)
                .flatMap(needDcStatusUpdate -> {
                    // import managed keyspace from plugins
                    List<CompletableSource> todoList = new ArrayList<>();
                    for (Plugin plugin : pluginRegistry.plugins()) {
                        if (plugin.isActive(dataCenter))
                            todoList.add(plugin.syncKeyspaces(CqlKeyspaceManager.this, dataCenter));
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(needDcStatusUpdate);
                })
                .flatMap(needDcStatusUpdate -> {
                    // create keyspace if needed
                    List<CompletableSource> todoList = new ArrayList<>();
                    if (get(dataCenter) != null) {
                        for (CqlKeyspace keyspace : get(dataCenter).values()) {
                            if (!dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().contains(keyspace.name)) {
                                try {
                                    needDcStatusUpdate = true;
                                    todoList.add(keyspace.createIfNotExistsKeyspace(dataCenter, sessionSupplier).ignoreElement());
                                    dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().add(keyspace.name);
                                } catch (Exception e) {
                                    logger.warn("datacenter=" + dataCenter.id() + " Failed to create keyspace=" + keyspace.name, e);
                                }
                            }
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(needDcStatusUpdate);
                })
                .flatMap(needDcStatusUpdate -> {
                    for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                        addIfAbsent(dataCenter, keyspace.name, () -> keyspace);
                    }
                    // reconcile keyspace according to the current DC size
                    List<CompletableSource> todoList = new ArrayList<>();
                    // if the last observed replicas and current replicas differ, update keyspaces
                    if (!Optional.ofNullable(dataCenter.getStatus().getKeyspaceManagerStatus().getReplicas()).orElse(0).equals(dataCenter.getSpec().getReplicas())) {
                        // adjust RF for system keyspaces
                        for (CqlKeyspace keyspace : get(dataCenter).values()) {
                            if (!keyspace.reconcilied() || keyspace.reconcileWithDcSize < keyspace.rf || dataCenter.getSpec().getReplicas() < keyspace.rf)
                                todoList.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier));
                        }

                        // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
                        String elasticAdminKeyspace = (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
                        try {
                            CqlKeyspace keyspace = get(dataCenter, elasticAdminKeyspace);
                            if (!keyspace.reconcilied() || keyspace.reconcileWithDcSize < keyspace.rf || dataCenter.getSpec().getReplicas() < keyspace.rf)
                                todoList.add(updateKeyspaceReplicationMap(dataCenter, elasticAdminKeyspace, effectiveRF(dataCenter, dataCenter.getSpec().getReplicas()), sessionSupplier));
                        } catch (Exception e) {
                            logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace=" + elasticAdminKeyspace, e);
                        }

                        // adjust user keyspace RF
                        if (get(dataCenter) != null) {
                            for (CqlKeyspace keyspace : get(dataCenter).values()) {
                                try {
                                    if (!keyspace.reconcilied() || keyspace.reconcileWithDcSize < keyspace.rf || dataCenter.getSpec().getReplicas() < keyspace.rf)
                                        todoList.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier));
                                } catch (Exception e) {
                                    logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace=" + keyspace, e);
                                }
                            }
                        }
                        // we set the current replicas in observed replicas to know if we need to update rf map
                        dataCenter.getStatus().getKeyspaceManagerStatus().setReplicas(dataCenter.getSpec().getReplicas());
                        return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(true);
                    } else {
                        return Single.just(needDcStatusUpdate);
                    }
                });
    }

    /**
     * Compute the effective target RF.
     * If DC is scaling up, increase the RF by 1 to automatically stream data to the new node.
     *
     * @param dataCenter
     * @param targetRf
     * @return
     */
    int effectiveRF(DataCenter dataCenter, int targetRf) {
        return Math.max(1, Math.min(targetRf, Math.min(dataCenter.getStatus().getReadyReplicas(), dataCenter.getSpec().getReplicas())));
    }

    public void removeDatacenter(final DataCenter dataCenter, CqlSessionSupplier sessionSupplier) throws Exception {
        // abort if dc is not running normally or not connected
        if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) && dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            try {
                // adjust RF for system keyspaces
                for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                    updateKeyspaceReplicationMap(dataCenter, keyspace.name, 0, sessionSupplier).blockingGet();
                }

                // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
                updateKeyspaceReplicationMap(dataCenter, elasticAdminKeyspaceName(dataCenter), 0, sessionSupplier).blockingGet();

                // adjust user keyspace RF
                if (get(dataCenter) != null) {
                    for (CqlKeyspace keyspace : get(dataCenter).values()) {
                        updateKeyspaceReplicationMap(dataCenter, keyspace.name, 0, sessionSupplier).blockingGet();
                    }
                }
            } catch (Exception e) {
                // TODO [ELE] should be ignored in single DC deployment but maybe retry in multiDC configuration...
                logger.warn("datacenter=" + dataCenter.id() + " Unable to update Keyspace Replication Map due to '{}'", e.getMessage(), e);
            }
        }
        remove(dataCenter);
    }

    public List<CqlKeyspace> getSystemAndElasticKeyspaces(final DataCenter dataCenter) {
        List<CqlKeyspace> keyspaces = new ArrayList<>();
        keyspaces.addAll(SYSTEM_KEYSPACES);
        keyspaces.add(new CqlKeyspace().setName(elasticAdminKeyspaceName(dataCenter)).setRf(3).setRepair(true));
        return keyspaces;
    }

    public Completable decreaseRfBeforeScalingDownDc(final DataCenter dataCenter, int targetDcSize, final CqlSessionSupplier sessionSupplier) throws Exception {
        if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) && dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            List<CqlKeyspace> keyspaces = getSystemAndElasticKeyspaces(dataCenter);
            if (get(dataCenter) != null)
                keyspaces.addAll(get(dataCenter).values());
            List<Completable> completables = new ArrayList<>(keyspaces.size());
            for (CqlKeyspace keyspace : keyspaces) {
                completables.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, Math.min(keyspace.rf, targetDcSize), sessionSupplier));
            }
            return Completable.mergeArray(completables.toArray(new Completable[completables.size()]))
                    .onErrorComplete();


        }
        return Completable.complete();
    }

    private Completable updateKeyspaceReplicationMap(final DataCenter dc, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier) throws Exception {
        return updateKeyspaceReplicationMap(dc, dc.getSpec().getDatacenterName(), keyspace, targetRf, sessionSupplier, true);
    }

    /**
     * Remove the DC from replication map of all keyspaces.
     *
     * @param dc
     * @param sessionSupplier
     * @return
     * @throws Exception
     */
    public Completable removeDcFromReplicationMap(final DataCenter dc, final String dcName, final CqlSessionSupplier sessionSupplier) throws Exception {
        return sessionSupplier.getSession(dc)
                .flatMap(session -> Single.fromFuture(session.executeAsync("SELECT keyspace_name, replication FROM system_schema.keyspaces")))
                .flatMapCompletable(rs -> {
                    final Map<String, Integer> currentRfMap = new HashMap<>();
                    List<Completable> todoList = new ArrayList<>();
                    for (Row row : rs) {
                        final Map<String, String> replication = row.getMap("replication", String.class, String.class);
                        final Map<String, Integer> keyspaceReplicationMap = replication.entrySet().stream()
                                .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> Integer.parseInt(e.getValue())));
                        if (keyspaceReplicationMap.containsKey(dcName)) {
                            keyspaceReplicationMap.remove(dcName);
                            todoList.add(alterKeyspace(dc, sessionSupplier, row.getString("keyspace_name"), keyspaceReplicationMap).ignoreElement());
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()]));
                })
                .onErrorComplete(t -> {
                    logger.error("datacenter=" + dc.id() + " remove dc=" + dcName + " error:", t);
                    return true;
                });
    }

    /**
     * Alter rf map but keep other dc replication factor
     *
     * @throws StrapkopException
     */
    public Completable updateKeyspaceReplicationMap(final DataCenter dc, String dcName, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier, boolean triggerRepairOrCleanup) throws Exception {
        return sessionSupplier.getSessionWithSchemaAgreed(dc)
                .flatMap(session ->
                        Single.fromFuture(session.executeAsync("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace))
                )
                .flatMapCompletable(rs -> {
                    Row row = rs.one();
                    if (row == null) {
                        logger.warn("datacenter={} keyspace={} does not exist, ignoring.", dc.id(), keyspace, dc.getMetadata().getName());
                        return Completable.complete();
                    }
                    final Map<String, String> replication = row.getMap("replication", String.class, String.class);
                    final String strategy = replication.get("class");
                    Objects.requireNonNull(strategy, "replication strategy cannot be null");

                    final Map<String, Integer> currentRfMap = replication.entrySet().stream()
                            .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> Integer.parseInt(e.getValue())
                            ));
                    final int currentRf = currentRfMap.getOrDefault(dcName, 0);
                    logger.debug("datacenter={} keyspace={} currentRf={} targetRf={}", dc.id(), keyspace, currentRf, targetRf);

                    // increase sequentially RF by one and repair to avoid quorum read issue (and elassandra_operator login issue)
                    Completable todo = Completable.complete();
                    for (int rf = currentRf; rf <= targetRf; rf++) {
                        currentRfMap.put(dcName, rf);
                        final int rf2 = rf;
                        if (currentRfMap.entrySet().stream().filter(e -> e.getValue() > 0).count() > 0) {
                            todo = todo.andThen(alterKeyspace(dc, sessionSupplier, keyspace, currentRfMap)
                                    .map(s -> {
                                        logger.debug("datacenter={} ALTER executed keyspace={} replicationMap={}", dc.id(), keyspace, currentRfMap);
                                        return s;
                                    })
                                    // check schema agreement and wait beyond the max schema agreement wait timeout
                                    .flatMap(s -> {
                                        if (!s.getCluster().getMetadata().checkSchemaAgreement())
                                            throw new IllegalStateException("No schema agreement");
                                        return Single.just(s);
                                    })
                                    .retryWhen((Flowable<Throwable> f) -> f.take(20).delay(6, TimeUnit.SECONDS))
                                    .map(s -> {
                                        logger.debug("datacenter={} ALTER applied keyspace={} replicationMap={}", dc.id(), keyspace, currentRfMap);
                                        CqlKeyspace cqlKeyspace = get(dc, keyspace);
                                        if (cqlKeyspace == null) {
                                            cqlKeyspace = new CqlKeyspace().withName(keyspace);
                                        }
                                        cqlKeyspace.setReconcilied(true);
                                        cqlKeyspace.setRf(rf2);
                                        cqlKeyspace.setReconcileWithDcSize(rf2);
                                        put(dc, cqlKeyspace.name, cqlKeyspace);
                                        return s;
                                    })
                                    .flatMapCompletable(s -> {
                                        if (!triggerRepairOrCleanup)
                                            return Completable.complete();

                                        if (targetRf > currentRf) {
                                            // RF increased
                                            if (targetRf > 1) {
                                                logger.info("datacenter={} Need a repair for keyspace={}", dc.id(), keyspace);
                                                final String labelSelector = OperatorLabels.toSelector(ImmutableMap.of(OperatorLabels.PARENT, dc.getMetadata().getName()));
                                                return Single.fromCallable(new Callable<Iterable<V1Pod>>() {
                                                    @Override
                                                    public Iterable<V1Pod> call() throws Exception {
                                                        return k8sResourceUtils.listNamespacedPods(dc.getMetadata().getNamespace(), null, labelSelector);
                                                    }
                                                }).flatMapCompletable(podList -> {
                                                    Completable todo2 = Completable.complete();
                                                    for (V1Pod pod : podList) {
                                                        logger.debug("Launch repair pod={} keyspace={}", pod.getMetadata().getName(), keyspace);
                                                        todo2 = todo2.andThen(jmxmpElassandraProxy.repair(ElassandraPod.fromName(dc, pod.getMetadata().getName()), keyspace));
                                                    }
                                                    return todo2;
                                                });
                                            }
                                        } else {
                                            // RF decreased
                                            if (targetRf < dc.getSpec().getReplicas()) {
                                                dc.getStatus().getNeedCleanupKeyspaces().add(keyspace);
                                                logger.info("datacenter={} cleanup required for keyspace={} in dc={}", dc.id(), keyspace);
                                            }
                                        }
                                        return Completable.complete();
                                    }));
                        }
                    }
                    return todo;
                })
                .onErrorComplete(t -> {
                    logger.error("datacenter=" + dc.id() + " update RF keyspace=" + keyspace + " error:", t);
                    return true;
                });
    }

    private Single<Session> alterKeyspace(final DataCenter dc, final CqlSessionSupplier sessionSupplier, final String name, Map<String, Integer> rfMap) throws Exception {
        return sessionSupplier.getSessionWithSchemaAgreed(dc)
                .flatMap(session -> {
                    final String query = String.format(Locale.ROOT,
                            "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                            quoteKeyspaceName(name), stringifyRfMap(rfMap));
                    logger.debug("dc={} query={}", dc.id(), query);
                    return Single.fromFuture(session.executeAsync(query)).map(x -> session);
                });
    }

    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> String.format("'%s': %d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }

    private String quoteKeyspaceName(final String keyspace) {
        return keyspace.startsWith("_") ? "\"" + keyspace + "\"" : keyspace;
    }
}
