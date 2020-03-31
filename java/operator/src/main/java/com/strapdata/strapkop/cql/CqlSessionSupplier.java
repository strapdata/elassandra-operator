package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface CqlSessionSupplier  {
    final Logger logger = LoggerFactory.getLogger(CqlSessionSupplier.class);

    /**
     * return a cassandra session
     * @param dataCenter
     * @return
     * @throws Exception
     */
    Single<Session> getSession(DataCenter dataCenter) throws Exception;

    /**
     * Return a session with agreed schema, meaning that we can safly update it
     * @param dataCenter
     * @return
     * @throws Exception
     */
    Single<Session> getSessionWithSchemaAgreed(DataCenter dataCenter) throws Exception;

    /**
     * Close cassandra session
     */
    void close();


    /*
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
    */
}
