package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.DriverException;
import com.google.common.collect.ImmutableList;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.ReaperStatus;
import com.strapdata.strapkop.exception.StrapkopException;
import io.kubernetes.client.models.V1Secret;
import lombok.*;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;


@FunctionalInterface
interface PostCreateHandler {
    void postCreate(DataCenter dataCenter, final Session session) throws Exception;
}

@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder=true)
public class CqlRole implements Cloneable {
    private static final Logger logger = LoggerFactory.getLogger(CqlRole.class);

    public static final CqlRole DEFAULT_CASSANDRA_ROLE = new CqlRole().withUsername("cassandra").withPassword("cassandra").withSuperUser(true).withApplied(true);

    public static final CqlRole CASSANDRA_ROLE = new CqlRole().withUsername("cassandra").withSuperUser(false).withApplied(false);
    public static final CqlRole ADMIN_ROLE = new CqlRole().withUsername("admin").withSuperUser(true).withApplied(false);
    public static final CqlRole STRAPKOP_ROLE = new CqlRole().withUsername("strapkop").withSuperUser(true).withApplied(false);
    public static final CqlRole REAPER_ROLE = new CqlRole()
            .withUsername("reaper")
            .withSuperUser(false)
            .withApplied(false)
            .withGrantStatements(ImmutableList.of("GRANT ALL PERMISSIONS ON KEYSPACE reaper_db TO reaper"))
            .withPostCreateHandler(CqlRole::postCreateReaper);

    public static void postCreateReaper(DataCenter dataCenter, final Session session) throws Exception {
        dataCenter.getStatus().setReaperStatus(ReaperStatus.ROLE_CREATED);
        logger.debug("reaper role created for dc={}, ReaperStatus=ROLE_CREATED", dataCenter.getMetadata().getName());
    }

    String username;
    @ToString.Exclude
    String password;
    boolean superUser;
    boolean applied;
    List<String> grantStatements;
    PostCreateHandler postCreateHandler;

    public CqlRole duplicate() {
        return this.toBuilder().applied(false).password(null).build();
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
        if (this.password.matches(".*[\"\'].*")) {
            throw new StrapkopException(String.format("invalid character in cassandra password for username %s", username));
        }
        return this;
    }

    /**
     * Create or update a cassandra role, grant permissions and execute postCreate handler.
     *
     * @param session
     * @return this
     * @throws StrapkopException
     */
    CqlRole createOrUpdateRole(DataCenter dataCenter, final Session session) throws Exception {
        if (!applied) {
            logger.debug("Creating role={} in cluster={} dc={}", this, dataCenter.getSpec().getClusterName(), dataCenter.getMetadata().getName());
            // create role if not exists, then alter... so this is completely idempotent and can even update password, although it might not be optimized
            session.execute(String.format(Locale.ROOT, "CREATE ROLE IF NOT EXISTS %s with SUPERUSER = %b AND LOGIN = true and PASSWORD = '%s'", username, superUser, password));
            session.execute(String.format(Locale.ROOT, "ALTER ROLE %s WITH SUPERUSER = %b AND LOGIN = true AND PASSWORD = '%s'", username, superUser, password));
            if (this.grantStatements != null) {
                for (String grant : grantStatements) {
                    try {
                        session.execute(grant);
                    } catch (DriverException ex) {
                        logger.error("Failed to execute: " + grant, ex);
                    }
                }
            }
            if (this.postCreateHandler != null) {
                try {
                    this.postCreateHandler.postCreate(dataCenter, session);
                } catch (Exception e) {
                    logger.error("Failed to execute posteCreate for role=" + this.username, e);
                }
            }
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
