package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Session;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FunctionalInterface
public interface CqlSessionSupplier {
    final Logger logger = LoggerFactory.getLogger(CqlSessionSupplier.class);

    Single<Session> getSession(DataCenter dataCenter) throws Exception;

    static void closeQuietly(Session session) {
        if (session != null ) {
            try {
                session.close();
            } catch (Exception e) {
                logger.trace("Exception on close session", e);
            }
        }
    }

    static void closeQuietly(Cluster cluster) {
        if (cluster != null ) {
            try {
                logger.debug("Closing cluster={} {}", cluster.getClusterName(), cluster);
                cluster.close();
            } catch (Exception e) {
                logger.trace("Exception on close cluster", e);
            }
        }
    }
}
