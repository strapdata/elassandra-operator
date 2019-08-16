package com.strapdata.strapkop.reconcilier;

import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.exception.StrapkopException;

import javax.inject.Singleton;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class KeyspacesManager {
    
    final private CqlConnectionManager cqlConnectionManager;
    
    public KeyspacesManager(CqlConnectionManager cqlConnectionManager) {
        this.cqlConnectionManager = cqlConnectionManager;
    }
    
    public void initializeReaperKeyspace(DataCenter dc) throws StrapkopException {
        // TODO: manage RF according to number of nodes and DC
        createKeyspace(dc, "reaper_db", ImmutableMap.of(dc.getSpec().getDatacenterName(), 1), true);
        dc.getStatus().setReaperKeyspaceInitialized(true);
    }
    
    public void createKeyspace(final DataCenter dc, final String name, Map<String, Integer> rfMap, boolean ifNotExists) throws StrapkopException {
        final Session session = getSession(dc);
        session.execute(String.format(
                "CREATE KEYSPACE %s %s WITH replication = {'class': 'NetworkTopologyStrategy', %s};",
                    ifNotExists ? "IF NOT EXISTS" : "", name, stringifyRfMap(rfMap)));
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
