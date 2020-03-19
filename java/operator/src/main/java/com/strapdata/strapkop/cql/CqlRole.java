package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.reactivex.Completable;
import io.reactivex.Single;
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

    public static final String KEY_CASSANDRA_PASSWORD = "cassandra.cassandra_password";
    public static final String KEY_ELASSANDRA_OPERATOR_PASSWORD = "cassandra.elassandra_operator_password";
    public static final String KEY_ADMIN_PASSWORD = "cassandra.admin_password";

    public static final CqlRole DEFAULT_CASSANDRA_ROLE = new CqlRole()
            .withUsername("cassandra")
            .withPassword("cassandra")
            .withSuperUser(true)
            .withLogin(true)
            .withApplied(true);

    public static final CqlRole CASSANDRA_ROLE = new CqlRole()
            .withUsername("cassandra")
            .withSecretKey(KEY_CASSANDRA_PASSWORD)
            .withSuperUser(true)
            .withLogin(true)
            .withApplied(false);

    public static final CqlRole ADMIN_ROLE = new CqlRole()
            .withUsername("admin")
            .withSecretKey(KEY_ADMIN_PASSWORD)
            .withSuperUser(true)
            .withLogin(true)
            .withApplied(false);

    public static final CqlRole STRAPKOP_ROLE = new CqlRole()
            .withUsername("elassandra_operator")
            .withSecretKey(KEY_ELASSANDRA_OPERATOR_PASSWORD)
            .withSuperUser(true)
            .withLogin(true)
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

    boolean login;

    boolean applied;



    /**
     * Grant statements applied after the role is created
     */
    List<String> grantStatements;

    /**
     * Handler called after the role is created.
     */
    PostCreateHandler postCreateHandler;

    public CqlRole duplicate() {
        return this.toBuilder().applied(false).password(null).build();
    }

    public String secret(DataCenter dc) {
        return (secretKey == null) ? null : secretNameProvider.apply(dc) + "/" + secretKey;
    }

    Single<CqlRole> loadPassword(DataCenter dataCenter, K8sResourceUtils k8sResourceUtils) {
        if (this.password != null)
            return Single.just(this);

        return k8sResourceUtils.readNamespacedSecret(dataCenter.getMetadata().getNamespace(), this.secretNameProvider.apply(dataCenter))
                .map(secret -> {
                    byte[] passBytes = secret.getData().get(secretKey);
                    if (passBytes == null) {
                        logger.error("datacenter={} secret={} does not contain password for role={}", dataCenter.id(), secret.getMetadata().getName(), this);
                        throw new StrapkopException("secret=" + secret.getMetadata().getName() + " does not contain password for role=" + username);
                    }
                    this.password = new String(passBytes);
                    if (this.password.matches(".*[\"\'].*")) {
                        throw new StrapkopException(String.format("invalid character in cassandra password for username %s", username));
                    }
                    return this;
                });
    }

    /**
     * Create or update a cassandra role, grant permissions and execute postCreate handler.
     *
     * @param sessionSupplier
     * @return this
     * @throws StrapkopException
     */
    Single<CqlRole> createOrUpdateRole(DataCenter dataCenter, K8sResourceUtils k8sResourceUtils, final CqlSessionSupplier sessionSupplier) throws Exception {
        if (!applied) {
            // create role if not exists, then alter... so this is completely idempotent and can even update password, although it might not be optimized
            return loadPassword(dataCenter, k8sResourceUtils)
                    .flatMap(cqlRole -> {
                        logger.debug("datacenter={} Creating role={}", dataCenter.id(), this);
                        return sessionSupplier.getSession(dataCenter);
                    })
                    .flatMap(session -> {
                        if (!"cassandra".equals(username)) {
                            // don not create the cassandra role, it always exists
                            String q = String.format(Locale.ROOT, "CREATE ROLE IF NOT EXISTS %s with SUPERUSER = %b AND LOGIN = %b and PASSWORD = '%s'", username, superUser, login, password);
                            logger.debug("datacenter={} query={}", dataCenter.id(), q);
                            return Single.fromFuture(session.executeAsync(q)).map(rs -> session);
                        } else {
                            return Single.just(session);
                        }
                    })
                    .flatMap(session -> {
                        String q = String.format(Locale.ROOT, "ALTER ROLE %s WITH PASSWORD = '%s'", username, password);
                        logger.debug("datacenter={} query={}", dataCenter.id(), q);
                        return Single.fromFuture(session.executeAsync(q)).map(rs -> session);
                    })
                    .flatMap(session -> {
                        return (this.grantStatements != null && this.grantStatements.size() > 0) ?
                                Completable.mergeArray(this.grantStatements.stream().map(
                                        stmt -> Completable.fromFuture(session.executeAsync(stmt))).toArray(Completable[]::new))
                                        .toSingleDefault(session) :
                                Single.just(session);
                    })
                    .map(session -> {
                        if (this.postCreateHandler != null) {
                            try {
                                this.postCreateHandler.postCreate(dataCenter, sessionSupplier);
                            } catch (Exception e) {
                                logger.error("datacenter="+ dataCenter.id()+" Failed to execute posteCreate for role=" + this.username, e);
                            }
                        }
                        this.applied = true;     // mark the role as up-to-date
                        return this;
                    });
        }
        return Single.just(this);
    }

    Single<CqlRole> deleteRole(DataCenter dataCenter, final Session session) throws Exception {
        logger.debug("Droping role={}", this);
        return Single.fromFuture(session.executeAsync(String.format(Locale.ROOT, "DROP ROLE %s", username)))
                .map(rs -> {
                    return this;
                });
    }

    /**
     * Update role password.
     *
     * @param session
     * @return this
     */
    Single<CqlRole> updatePassword(Session session) {
        logger.debug("Updating password for role={}", this);
        return Single.fromFuture(session.executeAsync(String.format(Locale.ROOT, "ALTER ROLE %s WITH PASSWORD = '%s'", username, password)))
                .map(rs -> this);
    }
}
