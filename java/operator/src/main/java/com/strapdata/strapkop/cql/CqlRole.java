package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.DriverException;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.models.V1Secret;
import lombok.*;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;


@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder=true)
public class CqlRole implements Cloneable {
    private static final Logger logger = LoggerFactory.getLogger(CqlRole.class);

    public static final CqlRole DEFAULT_CASSANDRA_ROLE = new CqlRole()
            .withUsername("cassandra")
            .withPassword("cassandra")
            .withSuperUser(true)
            .withApplied(true);

    public static final CqlRole CASSANDRA_ROLE = new CqlRole()
            .withUsername("cassandra")
            .withSecretKey("cassandra.cassandra_password")
            .withSuperUser(false)
            .withApplied(false);

    public static final CqlRole ADMIN_ROLE = new CqlRole()
            .withUsername("admin")
            .withSecretKey("cassandra.admin_password")
            .withSuperUser(true)
            .withApplied(false);

    public static final CqlRole STRAPKOP_ROLE = new CqlRole()
            .withUsername("strapkop")
            .withSecretKey("cassandra.strapkop_password")
            .withSuperUser(true)
            .withApplied(false);


    /**
     * Role name
     */
    String username;

    /**
     * Password loaded from a K8S secret.
     */
    @ToString.Exclude
    String password;

    /**
     * Function that return the secret name from the dataCenter.
     */
    @Builder.Default
    Function<DataCenter, String> secretNameProvider = dc -> OperatorNames.clusterSecret(dc);

    /**
     * K8s secret key name for the password.
     */
    String secretKey;

    boolean superUser;

    boolean applied;

    /**
     * Grant statement applied after the role is created
     */
    List<String> grantStatements;

    /**
     * Handler called after the role is created.
     */
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

        byte[] passBytes = secret.getData().get(secretKey);
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

            this.applied = true;     // mark the role as up-to-date

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
