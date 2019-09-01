package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.cassandra.ReaperStatus;
import com.strapdata.strapkop.exception.StrapkopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manage keyspace creation, adjust the replication factor for some <b>existing</b> keyspaces, and trigger repairs/cleanups accordingly.
 * Keyspace reconciliation must be made before role reconciliation.
 */
@Singleton
public class CqlKeyspaceManager extends AbstractManager<CqlKeyspace> {
    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);
    private static final Set<String> SYSTEM_KEYSPACES = new HashSet<>(Arrays.asList(new String[] { "system_auth", "system_distributed", "system_traces" }));

    public static final CqlKeyspace REAPER_KEYSPACE = new CqlKeyspace("reaper_db", 3) {
        @Override
        CqlKeyspace createIfNotExistsKeyspace(DataCenter dataCenter, Session session) {
            CqlKeyspace ks = super.createIfNotExistsKeyspace(dataCenter, session);
            dataCenter.getStatus().setReaperStatus(ReaperStatus.KEYSPACE_CREATED);
            return ks;
        }
    };

    public CqlKeyspaceManager(CqlConnectionManager cqlConnectionManager) {
        super(cqlConnectionManager);
    }

    private String elasticAdminKeyspaceName(DataCenter dataCenter) {
        return (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
    }

    public void reconcileKeyspaces(final DataCenter dataCenter) throws StrapkopException {

        if (dataCenter.getSpec().getReaperEnabled()) {
            add(dataCenter, REAPER_KEYSPACE.name, REAPER_KEYSPACE);
        } else {
            remove(dataCenter, REAPER_KEYSPACE.name);
        }

        final Session session = cqlConnectionManager.getConnection(dataCenter);
        if (session == null)
            return; // not connected

        // create keyspace if needed
        if (get(dataCenter) != null) {
            for (CqlKeyspace keyspace : get(dataCenter).values()) {
                try {
                    if (!dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().contains(keyspace.name)) {
                        keyspace.createIfNotExistsKeyspace(dataCenter, session);
                        dataCenter.getStatus().getKeyspaceManagerStatus().getKeyspaces().add(keyspace.name);
                    }
                } catch (Exception e) {
                    logger.error("Failed to create keyspace=" + keyspace.name, e);
                }
            }
        }

        // if the last observed replicas and current replicas differ, update keyspaces
        if (!Optional.ofNullable(dataCenter.getStatus().getKeyspaceManagerStatus().getReplicas()).orElse(0).equals(dataCenter.getStatus().getReplicas())) {

            // adjust RF for system keyspaces
            for (String keyspace : SYSTEM_KEYSPACES) {
                updateKeyspaceReplcationMap(dataCenter, keyspace, effectiveRF(dataCenter, 3));
            }

            // monitor elastic_admin keyspace to reduce RF when scaling down the DC.
            String elasticAdminKeyspace = (dataCenter.getSpec().getDatacenterGroup() != null) ? "elastic_admin_" + dataCenter.getSpec().getDatacenterGroup() : "elastic_admin";
            updateKeyspaceReplcationMap(dataCenter, elasticAdminKeyspace, dataCenter.getSpec().getReplicas());

            // adjust user keyspace RF
            if (get(dataCenter) != null) {
                for (CqlKeyspace keyspace : get(dataCenter).values()) {
                    updateKeyspaceReplcationMap(dataCenter, keyspace.name, effectiveRF(dataCenter, keyspace.rf));
                }
            }
            // we set the current replicas in observed replicas to know if we need to update rf map
            dataCenter.getStatus().getKeyspaceManagerStatus().setReplicas(dataCenter.getStatus().getReplicas());
        }
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
        for(CqlKeyspace keyspace : get(dataCenter).values()) {
            updateKeyspaceReplcationMap(dataCenter, keyspace.name, 0);
        }

        remove(dataCenter);
    }

    /**
     * Alter rf map but keep other dc replication factor
     * @throws StrapkopException
     */
    private void updateKeyspaceReplcationMap(final DataCenter dc, final String keyspace, int targetRf) throws StrapkopException {
        final Session session = cqlConnectionManager.getSessionRequireNonNull(dc);
        final Row row = session.execute("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace).one();
        if (row == null) {
            logger.warn("keyspace={} does not exist in dc={}, ignoring.", keyspace, dc.getMetadata().getName());
            return;
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

        if (currentRf != targetRf) {
            currentRfMap.put(dc.getSpec().getDatacenterName(), targetRf);

            if (currentRfMap.entrySet().stream().filter(e->e.getValue() > 0).count() == 0)
                return; // replication map is empty, ignoring update

            alterKeyspace(dc, keyspace, currentRfMap);
            if (targetRf > currentRf) {
                // increase RF
                logger.info("Trigger a repair for keyspace={} in dc={}", keyspace, dc.getMetadata().getName());
                // TODO: trigger repair if RF is manually increased while DC was RUNNING (if RF is incremented just before scaling up, streaming propely pull data)
            } else {
                // deacrease RF
                if (targetRf < dc.getStatus().getReplicas()) {
                    logger.info("Trigger a cleanup for keyspace={} in dc={}", keyspace, dc.getMetadata().getName());
                    // TODO: trigger cleanup when RF is manually decrease while DC was RUNNING.
                }
            }
        }
    }

    private void alterKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap) throws StrapkopException {
        final Session session = cqlConnectionManager.getSessionRequireNonNull(dc);
        final String query = String.format(Locale.ROOT,
                "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                name, stringifyRfMap(rfMap));
        logger.debug("dc={} execute: {}", dc.getMetadata().getName(), query);
        session.execute(query);
    }

    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> String.format("'%s': %d", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }
}
