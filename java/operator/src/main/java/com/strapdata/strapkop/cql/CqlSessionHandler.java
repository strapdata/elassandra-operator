package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.micronaut.context.annotation.Prototype;
import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            cqlRoleManager.connect(dataCenter)
                .map(tuple -> {
                    this.cluster = tuple._1;
                    this.session = tuple._2;
                    return this.session;
                });
    }

    public Completable close() throws Exception {
        return (cluster == null) ?
                Completable.complete() :
                Completable.fromAction(new Action() {
                    @Override
                    public void run() throws Exception {
                        logger.debug("Closing connections to cluster={}", cluster.getClusterName());
                        cluster.close();

                    }
                });
    }
}
