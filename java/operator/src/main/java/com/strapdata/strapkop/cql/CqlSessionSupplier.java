/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
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
    Single<Session> getSession(DataCenter dataCenter, DataCenterStatus status) throws Exception;

    /**
     * Return a session with agreed schema, meaning that we can safly update it
     * @param dataCenter
     * @return
     * @throws Exception
     */
    Single<Session> getSessionWithSchemaAgreed(DataCenter dataCenter, DataCenterStatus status) throws Exception;

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
