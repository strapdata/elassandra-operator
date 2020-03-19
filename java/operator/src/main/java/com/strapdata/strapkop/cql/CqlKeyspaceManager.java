package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableSet;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.model.k8s.cassandra.CqlStatus;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.task.CleanupTaskSpec;
import com.strapdata.strapkop.model.k8s.task.RepairTaskSpec;
import com.strapdata.strapkop.model.k8s.task.TaskSpec;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.reactivex.Completable;
import io.reactivex.CompletableSource;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manage keyspace creation, adjust the replication factor for some <b>existing</b> keyspaces, and trigger repairs/cleanups accordingly.
 * Keyspace reconciliation must be made before role reconciliation.
 */
@Singleton
public class CqlKeyspaceManager extends AbstractManager<CqlKeyspace> {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    public static final Set<CqlKeyspace> SYSTEM_KEYSPACES = ImmutableSet.of(
            new CqlKeyspace().withName("system_auth").withRf(3),
            new CqlKeyspace().withName("system_distributed").withRf(3),
            new CqlKeyspace().withName("system_traces").withRf(3));

    final K8sResourceUtils k8sResourceUtils;

    public CqlKeyspaceManager(final K8sResourceUtils k8sResourceUtils) {
        super();
        this.k8sResourceUtils = k8sResourceUtils;
    }

    private String elasticAdminKeyspaceName(DataCenter dataCenter) {
        return (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
    }

    /**
     * Create and adjust keyspace RF
     * @param dataCenter
     * @return
     * @throws StrapkopException
     */
    public Single<Boolean> reconcileKeyspaces(final DataCenter dataCenter, Boolean updateStatus, final CqlSessionSupplier sessionSupplier, PluginRegistry pluginRegistry) {
        return Single.just(updateStatus)
                .map(needDcStatusUpdate -> {
                    for(Plugin plugin : pluginRegistry.plugins()) {
                        if (plugin.isActive(dataCenter))
                            plugin.syncKeyspaces(CqlKeyspaceManager.this, dataCenter);
                    }
                    return needDcStatusUpdate;
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
                                } catch(Exception e) {
                                    logger.warn("datacenter=" + dataCenter.id() + " Failed to create keyspace="+keyspace.name, e);
                                }
                            }
                        }
                    }
                    return Completable.mergeArray(todoList.toArray(new CompletableSource[todoList.size()])).toSingleDefault(needDcStatusUpdate);
                })
                .flatMap(needDcStatusUpdate -> {
                    List<CompletableSource> todoList = new ArrayList<>();
                    // if the last observed replicas and current replicas differ, update keyspaces
                    if (!Optional.ofNullable(dataCenter.getStatus().getKeyspaceManagerStatus().getReplicas()).orElse(0).equals(dataCenter.getSpec().getReplicas())) {
                        // adjust RF for system keyspaces
                        for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                            try {
                                todoList.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier));
                            } catch (Exception e) {
                                logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace="+keyspace, e);
                            }
                        }

                        // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
                        String elasticAdminKeyspace = (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
                        try {
                            todoList.add(updateKeyspaceReplicationMap(dataCenter, elasticAdminKeyspace, effectiveRF(dataCenter, dataCenter.getSpec().getReplicas()), sessionSupplier));
                        } catch (Exception e) {
                            logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace="+elasticAdminKeyspace, e);
                        }

                        // adjust user keyspace RF
                        if (get(dataCenter) != null) {
                            for (CqlKeyspace keyspace : get(dataCenter).values()) {
                                try {
                                    todoList.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier));
                                } catch (Exception e) {
                                    logger.warn("datacenter=" + dataCenter.id() + " Failed to adjust RF for keyspace="+keyspace, e);
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

    public Completable decreaseRfBeforeScalingDownDc(final DataCenter dataCenter, int targetDcSize, final CqlSessionSupplier sessionSupplier) throws Exception {
        if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) && dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED))
        {
            List<CqlKeyspace> keyspaces = new ArrayList<>();
            keyspaces.addAll(SYSTEM_KEYSPACES);
            keyspaces.add(new CqlKeyspace().setName(elasticAdminKeyspaceName(dataCenter)).setRf(3));
            if (get(dataCenter) != null)
                keyspaces.addAll(get(dataCenter).values());
            List<Completable> completables = new ArrayList<>(keyspaces.size());
            for(CqlKeyspace keyspace : keyspaces) {
                completables.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, Math.min(keyspace.rf, targetDcSize), sessionSupplier));
            }
            return Completable.mergeArray(completables.toArray(new Completable[completables.size()]))
                    .onErrorComplete();


        }
        return Completable.complete();
    }

    private Completable updateKeyspaceReplicationMap(final DataCenter dc, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier) throws Exception {
        return updateKeyspaceReplicationMap(dc, dc.getSpec().getDatacenterName(), keyspace, targetRf, sessionSupplier, true)
                .onErrorComplete();
    }

    /**
     * Remove the DC from replication map of all keyspaces.
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
                .onErrorComplete();
    }

    /**
     * Alter rf map but keep other dc replication factor
     *
     * @throws StrapkopException
     */
    public Completable updateKeyspaceReplicationMap(final DataCenter dc, String dcName, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier, boolean triggerRepairOrCleanup) throws Exception {
        return sessionSupplier.getSession(dc)
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
                    if (currentRf != targetRf) {
                        currentRfMap.put(dcName, targetRf);
                        if (currentRfMap.entrySet().stream().filter(e -> e.getValue() > 0).count() > 0) {
                            return alterKeyspace(dc, sessionSupplier, keyspace, currentRfMap)
                                    .map(s -> {
                                        logger.debug("datacenter={} ALTER done keyspace={} replicationMap={}", dc.id(), keyspace, currentRfMap);
                                        return s;
                                    })
                                    .flatMapCompletable(s -> {
                                        if (!triggerRepairOrCleanup)
                                            return Completable.complete();

                                        if (targetRf > currentRf) {
                                            // RF increased
                                            if (targetRf > 1) {
                                                logger.info("datacenter={} Trigger a repair for keyspace={}", dc.id(), keyspace);
                                                // TODO: trigger repair if RF is manually increased while DC was RUNNING (if RF is incremented just before scaling up, streaming propely pull data)
                                                return k8sResourceUtils.createTask(dc, "repair", new Consumer<TaskSpec>() {
                                                    @Override
                                                    public void accept(TaskSpec taskSpec) {
                                                        taskSpec.setRepair(new RepairTaskSpec().setKeyspace(keyspace));
                                                    }
                                                }).ignoreElement();
                                            }
                                        } else {
                                            // RF deacreased
                                            if (targetRf < dc.getSpec().getReplicas()) {
                                                logger.info("datacenter={} Trigger a cleanup for keyspace={} in dc={}", dc.id(), keyspace);
                                                // TODO: trigger cleanup when RF is manually decrease while DC was RUNNING.
                                                return k8sResourceUtils.createTask(dc, "cleanup", new Consumer<TaskSpec>() {
                                                    @Override
                                                    public void accept(TaskSpec taskSpec) {
                                                        taskSpec.setCleanup(new CleanupTaskSpec().setKeyspace(keyspace));
                                                    }
                                                }).ignoreElement();
                                            }
                                        }
                                        return Completable.complete();
                                    });
                        }
                    }
                    return Completable.complete();
                })
                .onErrorComplete();
    }

    private Single<Session> alterKeyspace(final DataCenter dc, final CqlSessionSupplier sessionSupplier, final String name, Map<String, Integer> rfMap) throws Exception {
        return sessionSupplier.getSession(dc)
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
