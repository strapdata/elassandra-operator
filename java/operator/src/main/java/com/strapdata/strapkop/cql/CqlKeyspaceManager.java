package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableSet;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.task.CleanupTaskSpec;
import com.strapdata.model.k8s.task.RepairTaskSpec;
import com.strapdata.model.k8s.task.TaskSpec;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.reactivex.Completable;
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

    private static final Set<CqlKeyspace> SYSTEM_KEYSPACES = ImmutableSet.of(
            new CqlKeyspace().withName("system_auth").withRf(3),
            new CqlKeyspace().withName("system_distributed").withRf(3),
            new CqlKeyspace().withName("system_traces").withRf(3));

    final PluginRegistry pluginRegistry;
    final K8sResourceUtils k8sResourceUtils;

    public CqlKeyspaceManager(final PluginRegistry pluginRegistry, final K8sResourceUtils k8sResourceUtils) {
        super();
        this.pluginRegistry = pluginRegistry;
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
    public Completable reconcileKeyspaces(final DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) {
        return Completable.fromRunnable(new Runnable() {
            @Override
            public void run() {
                for(Plugin plugin : pluginRegistry.plugins()) {
                    if (plugin.isActive(dataCenter))
                        plugin.syncKeyspaces(CqlKeyspaceManager.this, dataCenter);
                }

                // create keyspace if needed
                if (get(dataCenter) != null) {
                    for (CqlKeyspace keyspace : get(dataCenter).values()) {
                        if (!dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().contains(keyspace.name)) {
                            try {
                                keyspace.createIfNotExistsKeyspace(dataCenter, sessionSupplier).blockingGet();
                                dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().add(keyspace.name);
                            } catch(Exception e) {
                                logger.warn("Failed to create keyspace="+keyspace.name, e);
                            }
                        }
                    }
                }

                // if the last observed replicas and current replicas differ, update keyspaces
                if (!Optional.ofNullable(dataCenter.getStatus().getKeyspaceManagerStatus().getReplicas()).orElse(0).equals(dataCenter.getStatus().getReplicas())) {

                    // adjust RF for system keyspaces
                    for (CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                        try {
                            updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier).blockingGet();
                        } catch (Exception e) {
                            logger.warn("Failed to adjust RF for keyspace="+keyspace, e);
                        }
                    }

                    // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
                    String elasticAdminKeyspace = (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
                    try {
                        updateKeyspaceReplicationMap(dataCenter, elasticAdminKeyspace, dataCenter.getSpec().getReplicas(), sessionSupplier).blockingGet();
                    } catch (Exception e) {
                        logger.warn("Failed to adjust RF for keyspace="+elasticAdminKeyspace, e);
                    }

                    // adjust user keyspace RF
                    if (get(dataCenter) != null) {
                        for (CqlKeyspace keyspace : get(dataCenter).values()) {
                            try {
                                updateKeyspaceReplicationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf), sessionSupplier).blockingGet();
                            } catch (Exception e) {
                                logger.warn("Failed to adjust RF for keyspace="+keyspace, e);
                            }
                        }
                    }
                    // we set the current replicas in observed replicas to know if we need to update rf map
                    dataCenter.getStatus().getKeyspaceManagerStatus().setReplicas(dataCenter.getStatus().getReplicas());
                }
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
        return Math.max(1, Math.min(targetRf, Math.min(dataCenter.getStatus().getReadyReplicas() + 1, dataCenter.getSpec().getReplicas())));
    }

    public void removeDatacenter(final DataCenter dataCenter, CqlSessionSupplier sessionSupplier) throws Exception {
        // abort if dc is not running normally or not connected
        if (dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) && dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            // adjust RF for system keyspaces
            for(CqlKeyspace keyspace : SYSTEM_KEYSPACES) {
                updateKeyspaceReplicationMap(dataCenter, keyspace.name, 0, sessionSupplier).blockingGet();
            }

            // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
            updateKeyspaceReplicationMap(dataCenter, elasticAdminKeyspaceName(dataCenter), 0, sessionSupplier).blockingGet();

            // adjust user keyspace RF
            for(CqlKeyspace keyspace : get(dataCenter).values()) {
                updateKeyspaceReplicationMap(dataCenter, keyspace.name, 0, sessionSupplier).blockingGet();
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
            keyspaces.addAll(get(dataCenter).values());
            List<Completable> completables = new ArrayList<>(keyspaces.size());
            for(CqlKeyspace keyspace : keyspaces) {
                completables.add(updateKeyspaceReplicationMap(dataCenter, keyspace.name, Math.min(keyspace.rf, targetDcSize), sessionSupplier));
            }
            return Completable.mergeArray(completables.toArray(new Completable[completables.size()]));
        }
        return Completable.complete();
    }

    /**
     * Alter rf map but keep other dc replication factor
     *
     * @throws StrapkopException
     */
    private Completable updateKeyspaceReplicationMap(final DataCenter dc, final String keyspace, int targetRf, final CqlSessionSupplier sessionSupplier) throws Exception {
        return sessionSupplier.getSession(dc)
                .flatMap(session ->
                        Single.fromFuture(session.executeAsync("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace))
                )
                .flatMapCompletable(rs -> {
                    Row row = rs.one();
                    if (row == null) {
                        logger.warn("keyspace={} does not exist in dc={}, ignoring.", keyspace, dc.getMetadata().getName());
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
                    final int currentRf = currentRfMap.getOrDefault(dc.getSpec().getDatacenterName(), 0);
                    logger.debug("keyspace={} currentRf={} targetRf={}", keyspace, currentRf, targetRf);
                    if (currentRf != targetRf) {
                        currentRfMap.put(dc.getSpec().getDatacenterName(), targetRf);
                        if (currentRfMap.entrySet().stream().filter(e -> e.getValue() > 0).count() > 0) {
                            return alterKeyspace(dc, sessionSupplier, keyspace, currentRfMap)
                                    .flatMapCompletable(s -> {
                                        if (targetRf > currentRf) {
                                            // RF increased
                                            if (targetRf > 1) {
                                                logger.info("Trigger a repair for keyspace={} in dc={}", keyspace, dc.getMetadata().getName());
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
                                            if (targetRf < dc.getStatus().getReplicas()) {
                                                logger.info("Trigger a cleanup for keyspace={} in dc={}", keyspace, dc.getMetadata().getName());
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
                });
    }

    private Single<Session> alterKeyspace(final DataCenter dc, final CqlSessionSupplier sessionSupplier, final String name, Map<String, Integer> rfMap) throws Exception {
        return sessionSupplier.getSession(dc)
                .flatMap(session -> {
                    final String query = String.format(Locale.ROOT,
                            "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                            name, stringifyRfMap(rfMap));
                    logger.debug("dc={} execute: {}", dc.getMetadata().getName(), query);
                    return Single.fromFuture(session.executeAsync(query)).map(x -> session);
                });
    }

    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> String.format("'%s': %d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }
}
