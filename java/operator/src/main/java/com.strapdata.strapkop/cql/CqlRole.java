package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.ReaperStatus;
import com.strapdata.strapkop.exception.StrapkopException;
import io.kubernetes.client.models.V1Secret;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

@Data
@Wither
@Builder(toBuilder=true)
@NoArgsConstructor
@AllArgsConstructor
public class CqlRole {
    private static final Logger logger = LoggerFactory.getLogger(CqlRole.class);

    public static final CqlRole DEFAULT_CASSANDRA_ROLE = new CqlRole().withUsername("cassandra").withPassword("cassandra");

    public static final CqlRole REAPER_ROLE = new CqlRole() {
                @Override
                public CqlRole createOrUpdateRole(final DataCenter dataCenter, final Session session, final CqlRoleManager cqlRoleManager) throws Exception {
                    super.createOrUpdateRole(dataCenter, session, cqlRoleManager);
                    if (isApplied())
                        dataCenter.getStatus().setReaperStatus(ReaperStatus.KEYSPACE_INITIALIZED);
                    return this;
                }
            }
            .withUsername("reaper")
            .withSuperUser(false)
            .withKeyspaceRf(ImmutableMap.of("reaper_db", 3))
            .withGrantStatements(ImmutableList.of("GRANT ALL PERMISSIONS ON KEYSPACE \"reaper_db\" TO reaper"));

    String username;
    private String password;
    boolean superUser;
    boolean applied;

    Map<String, Integer> keyspaceRf;    // keyspace map to create keyspace before granting permissions
    Collection<String> grantStatements; // permissions to grant

    /**
     * Load password from k8s secret
     * @param dataCenter
     * @param secret
     * @return this
     * @throws StrapkopException
     */
    CqlRole loadPassword(final DataCenter dataCenter, V1Secret secret) throws StrapkopException {
        byte[] passBytes = secret.getData().get(String.format(Locale.ROOT, "cassandra.%s_password", username));
        if (passBytes == null) {
            logger.error("secret={} does not contain password for role={}", secret.getMetadata().getName(), username);
            throw new StrapkopException("secret="+secret.getMetadata().getName()+" does not contain password for role="+username);
        }
        this.password = new String(passBytes);
        return this;
    }

    /**
     * Create or update a cassandra role
     *
     * @param session
     * @throws StrapkopException
     * @return this
     */
    CqlRole createOrUpdateRole(final DataCenter dataCenter,
                               final Session session,
                               final CqlRoleManager cqlRoleManager) throws Exception {

        if (getPassword().matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", getUsername()));
        }

        // create role if not exists, then alter... so this is completely idempotent and can even update password, although it might not be optimized
        session.execute(String.format(Locale.ROOT,"CREATE ROLE IF NOT EXISTS %s with SUPERUSER = %b AND LOGIN = true and PASSWORD = '%s'", getUsername(), isSuperUser(), getPassword()));
        session.execute(String.format(Locale.ROOT,"ALTER ROLE %s WITH SUPERUSER = %b AND LOGIN = true AND PASSWORD = '%s'", getUsername(), isSuperUser(), getPassword()));

        // create keyspace if not exists and register for RF adjustment.
        if (keyspaceRf != null && keyspaceRf.size() > 0) {
            for(Map.Entry<String, Integer> entry : keyspaceRf.entrySet()) {
                int targetRf = Math.max(1, Math.min(entry.getValue(), dataCenter.getStatus().getReplicas()));
                session.execute(String.format(Locale.ROOT,"CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH replication = {'class': 'NetworkTopologyStrategy', '%s':'%d'}; ",
                        entry.getKey(), dataCenter.getMetadata().getName(), targetRf));
                if (entry.getValue() > 1) {
                    cqlRoleManager.keyspaceReplicationManager.addUserKeyspace(dataCenter, entry.getKey(), entry.getValue());
                }
            }
        }

        // grant permissions
        if (grantStatements != null) {
            for (String grantStmt : grantStatements)
                session.execute(grantStmt);
        }

        setApplied(true);
        return this;
    }

    CqlRole deleteRole(final DataCenter dataCenter,
                       Session session,
                       CqlRoleManager cqlRoleManager) throws Exception {
        session.execute(String.format(Locale.ROOT,"DROP ROLE %s", getUsername()));
        return this;
    }

    /**
     * Update role password.
     * @param session
     * @return this
     */
    CqlRole updatePassword(Session session) {
        session.execute(String.format(Locale.ROOT, "ALTER ROLE %s WITH PASSWORD = '%s'", getUsername(), getPassword()));
        return this;
    }

}
