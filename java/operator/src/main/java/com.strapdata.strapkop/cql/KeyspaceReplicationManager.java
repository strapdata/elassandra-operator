package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.exception.StrapkopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Only adjust the replication factor for some <b>existing</b> keyspaces, and trigger repairs/cleanups accordingly.
 */
@Singleton
public class KeyspaceReplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(KeyspaceReplicationManager.class);
    private static final Set<String> SYSTEM_KEYSPACES = new HashSet<>(Arrays.asList(new String[] { "system_auth", "system_distributed", "system_traces" }));

    private final CqlConnectionManager cqlConnectionManager;
    private final ConcurrentMap<String, Integer> userKeyspaces = new ConcurrentHashMap<>();

    public KeyspaceReplicationManager(CqlConnectionManager cqlConnectionManager) {
        this.cqlConnectionManager = cqlConnectionManager;
    }

    private String keyspaceKey(final DataCenter dataCenter, final String keyspace) {
        return dataCenter.getMetadata().getNamespace()+"/"+dataCenter.getSpec().getClusterName()+"/"+dataCenter.getMetadata().getName()+"/"+keyspace;
    }

    private String elasticAdminKeyspaceName(DataCenter dataCenter) {
        return (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
    }

    /**
     * Add a user keyspace.
     * @param keyspace
     * @param targetReplicationFactor is the desired replication factor in the datacenter
     */
    public void addUserKeyspace(final DataCenter dataCenter, final String keyspace, final int targetReplicationFactor) {
        userKeyspaces.put(keyspaceKey(dataCenter, keyspace), targetReplicationFactor);
    }

    /**
     * Remove a user keyspace from managed keyspaces
     * @param keyspace
     */
    public void removeUserKeyspace(final DataCenter dataCenter, final String keyspace) {
        userKeyspaces.remove(keyspaceKey(dataCenter, keyspace));
    }

    public void reconcileKeyspaces(final DataCenter dataCenter) throws StrapkopException {
        // abort if dc is not running normally or not connected
        if (!dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) || !dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            return;
        }

        // if the last observed replicas and current replicas differ, update keyspaces
        if (!Optional.ofNullable(dataCenter.getStatus().getKeyspaceManagerReplicas()).orElse(0).equals(dataCenter.getStatus().getReplicas())) {

            // adjust RF for system keyspaces
            for(String keyspace : SYSTEM_KEYSPACES) {
                int targetRf = Math.max(1, Math.min(3, dataCenter.getStatus().getReplicas()));
                updateKeyspaceReplcationMap(dataCenter, keyspace, targetRf);
            }

            // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
            String elasticAdminKeyspace = (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
            updateKeyspaceReplcationMap(dataCenter, elasticAdminKeyspace, dataCenter.getSpec().getReplicas());

            // adjust user keyspace RF
            for(String keyspace : userKeyspaces.keySet()) {
                int targetRf = Math.max(1, Math.min(userKeyspaces.get(keyspace), dataCenter.getStatus().getReplicas()));
                updateKeyspaceReplcationMap(dataCenter, keyspace, targetRf);
            }

            // we set the current replicas in observed replicas to know if we need to update rf map
            dataCenter.getStatus().setKeyspaceManagerReplicas(dataCenter.getStatus().getReplicas());
        }
    }

    /**
     * Remove the DC from all replication maps before destroying the datacenter
     * @param dataCenter
     */
    public void removeDatacenter(final DataCenter dataCenter) throws StrapkopException {
        // abort if dc is not running normally or not connected
        if (!dataCenter.getStatus().getPhase().equals(DataCenterPhase.RUNNING) || !dataCenter.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            return;
        }

        // adjust RF for system keyspaces
        for(String keyspace : SYSTEM_KEYSPACES) {
            updateKeyspaceReplcationMap(dataCenter, keyspace, 0);
        }

        // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
        updateKeyspaceReplcationMap(dataCenter, elasticAdminKeyspaceName(dataCenter), 0);

        // adjust user keyspace RF
        for(String keyspace : userKeyspaces.keySet()) {
            updateKeyspaceReplcationMap(dataCenter, keyspace, 0);
        }
    }

    /**
     * Alter rf map but keep other dc replication factor
     * @throws StrapkopException
     */
    private void updateKeyspaceReplcationMap(final DataCenter dc, final String keyspace, int targetRf) throws StrapkopException {
        final Session session = cqlConnectionManager.getSessionRequireNonNull(dc);
        final Row row = session.execute("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace).one();
        if (row == null) {
            logger.warn("keyspace={} does not exist in cluster={} dc={}, ignoring.", keyspace, dc.getSpec().getClusterName(), dc.getMetadata().getName());
            return;
        }
        final Map<String, String> replication = row.getMap("replication", String.class, String.class);
        final String strategy = replication.get("class");
        Objects.requireNonNull(strategy, "replication strategy cannot be null");
        if (!strategy.contains("NetworkTopologyStrategy")) {
            logger.warn("keyspace={} does not use the NetworkTopologyStrategy in cluster={} dc={}, ignoring.", keyspace, dc.getSpec().getClusterName(), dc.getMetadata().getName());
            return;
        }

        final Map<String, Integer> currentRfMap = replication.entrySet().stream()
                .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Integer.parseInt(e.getValue())
                ));
        final int currentRf = currentRfMap.getOrDefault(dc.getMetadata().getName(), 0);

        if (currentRf != targetRf) {
            currentRfMap.put(dc.getMetadata().getName(), targetRf);
            alterKeyspace(dc, keyspace, currentRfMap);
            if (targetRf > currentRf) {
                // increase RF
                logger.info("Trigger a repair for keyspace={} in cluster={} dc={}", keyspace, dc.getSpec().getClusterName(), dc.getMetadata().getName());
                // TODO: trigger repair
            } else {
                // deacrease RF
                if (targetRf < dc.getStatus().getReplicas()) {
                    logger.info("Trigger a cleanup for keyspace={} in cluster={} dc={}", keyspace, dc.getSpec().getClusterName(), dc.getMetadata().getName());
                    // TODO: trigger cleanup
                }
            }
        }
    }

    private void alterKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap) throws StrapkopException {
        final Session session = cqlConnectionManager.getSessionRequireNonNull(dc);
        final String query = String.format(Locale.ROOT,
                "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                name, stringifyRfMap(rfMap));
        logger.debug("cluster={} dc={} execute: {}", dc.getSpec().getClusterName(), dc.getMetadata().getName(), query);
        session.execute(query);
    }

    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> String.format("'%s': %d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }
}
