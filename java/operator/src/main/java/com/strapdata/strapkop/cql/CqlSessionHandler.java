package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Manage CQL session during a reconciliation
 */
@Prototype
public class CqlSessionHandler implements CqlSessionSupplier {
    private static final Logger logger = LoggerFactory.getLogger(CqlSessionHandler.class);

    final CqlRoleManager cqlRoleManager;

    Session session;
    Cluster cluster;

    public CqlSessionHandler(final CqlRoleManager cqlRoleManager) {
        this.cqlRoleManager = cqlRoleManager;
    }

    @Override
    public Single<Session> getSession(DataCenter dataCenter) throws Exception {
        return (session != null) ?
            Single.just(session) :
            cqlRoleManager.connect(dataCenter, dataCenter.getStatus())
                .map(tuple -> {
                    this.cluster = tuple._1;
                    this.session = tuple._2;
                    return this.session;
                });
    }

    @Override
    public void close() {
        logger.debug("Closing cluster={}", cluster == null ? null : cluster.getClusterName());
        if (cluster != null)
            cluster.closeAsync();
        cluster = null;
        session = null;
    }


    @Override
    public Single<Session> getSessionWithSchemaAgreed(DataCenter dataCenter) throws Exception {
        return getSession(dataCenter)
                .flatMap(s -> {
                    if (!s.getCluster().getMetadata().checkSchemaAgreement())
                        throw new IllegalStateException("No schema agreement");
                    return Single.just(session);
                })
                .retryWhen((Flowable<Throwable> f) -> f.take(10).delay(6, TimeUnit.SECONDS));
    }


}
