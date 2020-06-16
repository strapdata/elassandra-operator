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

import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.reactivex.Single;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

@Data
@With
@NoArgsConstructor
@AllArgsConstructor
public class CqlKeyspace implements CqlReconciliable {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    // spec
    /**
     * Keyspace name
     */
    String name;

    /**
     * Keyspace desired replication factor
     */
    int rf;

    /**
     * Create keyspace if not exists
     */
    boolean createIfNotExists = true;

    /**
     * Enable keyspace repair
     */
    boolean repair;

    // status
    boolean reconcilied;
    int reconcileWithDcSize;     // size of the DC when reconcilied

    @Override
    public boolean reconcilied() {
        return reconcilied;
    }

    /**
     * create keyspace if not exists.
     *
     * @param dataCenter
     * @param sessionSupplier
     * @return
     */
    public Single<CqlKeyspace> createIfNotExistsKeyspace(final DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) throws Exception {
        return (rf <= 0) ?
                Single.just(this) :
                sessionSupplier.getSessionWithSchemaAgreed(dataCenter)
                        .flatMap(session -> {
                        int targetRf = Math.max(1, Math.min(rf, dataCenter.getSpec().getReplicas()));
                        String query = String.format(Locale.ROOT, "CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH replication = {'class': 'NetworkTopologyStrategy', '%s':'%d'}; ",
                                name, dataCenter.getSpec().getDatacenterName(), targetRf);
                        logger.debug("dc={} query={}", dataCenter.id(), query);
                        return Single.fromFuture(session.executeAsync(query));
                    })
                    .map(x -> this);
    }
}
