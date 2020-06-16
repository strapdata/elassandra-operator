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

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.strapdata.elasticsearch.plugin.license.License;
import com.strapdata.elasticsearch.plugin.license.LicenseVerifierService;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Singleton
@Infrastructure
public class CqlLicenseManager extends AbstractManager<CqlLicense> implements LicenseVerifierService {

    private static final Logger logger = LoggerFactory.getLogger(CqlLicenseManager.class);
    private static final String SELECT_STATEMENT = "SELECT * from elastic_admin.licenses";

    public static final String LICENSE_KEY = "license";

    public CqlLicenseManager(final MeterRegistry meterRegistry) {
        super(meterRegistry);
    }

    public Completable verifyLicense(DataCenter dataCenter, CqlSessionHandler sessionHandler) {
        return Single.just(sessionHandler)
                .flatMap(handler -> sessionHandler.getSession(dataCenter))
                .flatMapCompletable(session -> {
                    try {
                        logger.trace(SELECT_STATEMENT);
                        ResultSet rs = session.execute(session.prepare(SELECT_STATEMENT).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM).bind());
                        Stream<License> rowStream = StreamSupport.stream(rs.spliterator(), false).map(CqlLicense::fromRow);

                        License license = verifyLicenses(rowStream, dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName());
                        logger.debug("datacenter={} license={}", dataCenter.id(), license);
                        if (license == null) {
                            logger.warn("datacenter={} No license found", dataCenter.id());
                            return Completable.complete();
                        } else if (license.isExpired()) {
                            logger.warn("datacenter={} Expired license={}", dataCenter.id(), license);
                            return Completable.complete();
                        } else {
                            addIfAbsent(dataCenter, LICENSE_KEY, () -> new CqlLicense().withReconcilied(true));
                        }
                    } catch(AuthenticationException | NoHostAvailableException e) {
                        logger.warn("datacenter="+dataCenter.id()+" Unable to get the Elassandra License", e);
                    }
                    return Completable.complete();
                });
    }
}
