package com.strapdata.strapkop.reconcilier;

import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.AlreadyExistsException;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.CqlStatus;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.exception.StrapkopException;

import javax.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Singleton
public class KeyspacesManager {
    
    final private CqlConnectionManager cqlConnectionManager;
    
    public KeyspacesManager(CqlConnectionManager cqlConnectionManager) {
        this.cqlConnectionManager = cqlConnectionManager;
    }
    
    public void reconcileKeyspaces(final DataCenter dc) throws StrapkopException {
        // abort if dc is not running normally
        if (!dc.getStatus().getPhase().equals(DataCenterPhase.RUNNING)) {
            return;
        }
        
        // abort if cql connection is not established
        if (!dc.getStatus().getCqlStatus().equals(CqlStatus.ESTABLISHED)) {
            return;
        }

        // if the last observed replicas and current replicas differ, update keyspaces
        if (!Optional.ofNullable(dc.getStatus().getKeyspaceStatuses().getObservedReplicas()).orElse(0).equals(dc.getStatus().getReplicas())) {

            final Map<String, Integer> rfMap = ImmutableMap.of(
                    dc.getSpec().getDatacenterName(),
                    Math.max(1, Math.min(3, dc.getStatus().getReplicas()))
            );
    
            updateReaperKeyspace(dc, rfMap);
            updateSystemKeyspaces(dc, rfMap);
    
            // we set the current replicas in observed replicas to know if we need to update rf map
            dc.getStatus().getKeyspaceStatuses().setObservedReplicas(dc.getStatus().getReplicas());
    
        }
    }
    
    private void updateSystemKeyspaces(DataCenter dc, Map<String, Integer> rfMap) throws StrapkopException {
        partialAlterKeyspace(dc, "system_auth", rfMap);
        partialAlterKeyspace(dc, "system_distributed", rfMap);
        partialAlterKeyspace(dc, "system_traces", rfMap);
    }
    
    private void updateReaperKeyspace(DataCenter dc, Map<String, Integer> rfMap) throws StrapkopException {
        if (!Objects.equals(dc.getStatus().getKeyspaceStatuses().getReaperInitialized(), true)) {
            createOrPartialAlterKeyspace(dc, "reaper_db", rfMap);
            dc.getStatus().getKeyspaceStatuses().setReaperInitialized(true);
        }
        else {
            partialAlterKeyspace(dc, "reaper_db", rfMap);
        }
    }
    
    private void createKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap, boolean ifNotExists) throws StrapkopException {
        final Session session = getSession(dc);
        session.execute(String.format(
                "CREATE KEYSPACE %s %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                ifNotExists ? "IF NOT EXISTS" : "", name, stringifyRfMap(rfMap)));
    }
    
    private void alterKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap) throws StrapkopException {
        final Session session = getSession(dc);
        session.execute(String.format(
                "ALTER KEYSPACE %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                name, stringifyRfMap(rfMap)));
    }
    
    /**
     * Try to create the keyspace and, if it already exist, partial alter rfMap
     * @throws StrapkopException
     */
    private void createOrPartialAlterKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap) throws StrapkopException {
        try {
            createKeyspace(dc, name, rfMap, false);
        }
        catch (AlreadyExistsException e) {
            partialAlterKeyspace(dc, name, rfMap);
        }
    }
    
    /**
     * Alter rf map but keep other dc replication factor
     * @throws StrapkopException
     */
    private void partialAlterKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap) throws StrapkopException {
        final Session session = getSession(dc);
        final Row row = session.execute("SELECT keyspace_name, replication FROM system_schema.keyspaces WHERE keyspace_name = ?", name).one();
        if (row == null) {
            throw new StrapkopException(String.format("keyspace=%s does not exist in dc=%s", name, dc.getMetadata().getName()));
        }
        final Map<String, String> replication = row.getMap("replication", String.class, String.class);
        final Map<String, Integer> mergeRfMap = replication.entrySet().stream()
                .filter(e -> !e.getKey().equals("class") && !e.getKey().equals("replication_factor"))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> Integer.parseInt(e.getValue())
                ));
        mergeRfMap.putAll(rfMap);
        alterKeyspace(dc, name, mergeRfMap);
    }
    
    
    private Session getSession(DataCenter dc) throws StrapkopException {
        final Session session = cqlConnectionManager.getConnection(dc);
        
        if (session == null) {
            throw new StrapkopException("no cql connection available to initialize reaper keyspace");
        }
        
        return session;
    }
    
    private String stringifyRfMap(final Map<String, Integer> rfMap) {
        return rfMap.entrySet().stream().map(e -> String.format("'%s': %d", e.getKey(), e.getValue())).collect(Collectors.joining(", "));
    }
}
