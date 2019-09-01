package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.ReaperStatus;
import com.strapdata.strapkop.exception.StrapkopException;
import io.kubernetes.client.models.V1Secret;
import lombok.*;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder=true)
public class CqlRole implements Cloneable {
    private static final Logger logger = LoggerFactory.getLogger(CqlRole.class);

    public static final CqlRole DEFAULT_CASSANDRA_ROLE = new CqlRole().setUsername("cassandra").setPassword("cassandra").setSuperUser(true).setApplied(true);
    public static final CqlRole CASSANDRA_ROLE = new CqlRole().setUsername("cassandra").setSuperUser(true);
    public static final CqlRole STRAPKOP_ROLE = new CqlRole().setUsername("strapkop").setSuperUser(true);
    public static final CqlRole ADMIN_ROLE = new CqlRole().setUsername("admin").setSuperUser(true);

    public static final CqlRole REAPER_ROLE = new CqlRole() {
        @Override
        CqlRole createOrUpdateRole(DataCenter dataCenter, final Session session) throws Exception {
            CqlRole role = super.createOrUpdateRole(dataCenter, session);
            session.execute("GRANT ALL PERMISSIONS ON KEYSPACE reaper_db TO reaper");
            dataCenter.getStatus().setReaperStatus(ReaperStatus.ROLE_CREATED);
            logger.debug("reaper role created for dc={}", dataCenter.getMetadata().getName());
            return role;
        }
    }.setUsername("reaper").setSuperUser(false);

    String username;
    @ToString.Exclude String password;
    boolean superUser;
    boolean applied;

    public CqlRole duplicate() {
        return this.toBuilder().password(null).applied(false).build();
    }
        /**
         * Load password from k8s secret
         *
         * @param secret
         * @return this
         * @throws StrapkopException
         */
    CqlRole loadPassword(V1Secret secret) throws StrapkopException {
        if (this.password != null)
            return this;

        byte[] passBytes = secret.getData().get(String.format(Locale.ROOT, "cassandra.%s_password", username));
        if (passBytes == null) {
            logger.error("secret={} does not contain password for role={}", secret.getMetadata().getName(), this);
            throw new StrapkopException("secret=" + secret.getMetadata().getName() + " does not contain password for role=" + username);
        }
        this.password = new String(passBytes);
        return this;
    }

    /**
     * Create or update a cassandra role
     *
     * @param session
     * @return this
     * @throws StrapkopException
     */
    CqlRole createOrUpdateRole(DataCenter dataCenter, final Session session) throws Exception {
        if (password.matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", username));
        }
        if (!applied) {
            logger.debug("Creating role={} in cluster={} dc={}", this, dataCenter.getSpec().getClusterName(), dataCenter.getMetadata().getName());
            // create role if not exists, then alter... so this is completely idempotent and can even update password, although it might not be optimized
            session.execute(String.format(Locale.ROOT, "CREATE ROLE IF NOT EXISTS %s with SUPERUSER = %b AND LOGIN = true and PASSWORD = '%s'", username, superUser, password));
            session.execute(String.format(Locale.ROOT, "ALTER ROLE %s WITH SUPERUSER = %b AND LOGIN = true AND PASSWORD = '%s'", username, superUser, password));
            this.applied = true;
        }
        return this;
    }

    CqlRole deleteRole(final Session session) throws Exception {
        logger.debug("Droping role={}", this);
        session.execute(String.format(Locale.ROOT, "DROP ROLE %s", username));
        return this;
    }

    /**
     * Update role password.
     *
     * @param session
     * @return this
     */
    CqlRole updatePassword(Session session) {
        logger.debug("Updating password for role={}", this);
        session.execute(String.format(Locale.ROOT, "ALTER ROLE %s WITH PASSWORD = '%s'", username, password));
        return this;
    }


}
