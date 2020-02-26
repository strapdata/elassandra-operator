package com.strapdata.strapkop.cql;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.exceptions.NoHostAvailableException;
import com.strapdata.elasticsearch.plugin.license.License;
import com.strapdata.elasticsearch.plugin.license.LicenseVerifierService;
import com.strapdata.strapkop.exception.InvalidLicenseException;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Singleton
public class CqlLicenseManager extends AbstractManager<License> implements LicenseVerifierService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CqlLicenseManager.class);
    public static final String SELECT_STATEMENT = "SELECT * from elastic_admin.licenses";

    public Completable verifyLicense(DataCenter dataCenter, CqlSessionHandler sessionHandler) {
        return Single.just(sessionHandler)
                .flatMap(handler -> sessionHandler.getSession(dataCenter))
                .map(session -> {
                    try {
                        LOGGER.trace(SELECT_STATEMENT);
                        ResultSet rs = session.execute(session.prepare(SELECT_STATEMENT).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM).bind());
                        Stream<License> rowStream = StreamSupport.stream(rs.spliterator(), false).map(CqlLicense::fromRow);

                        License license = verifyLicenses(rowStream, dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName());
                        LOGGER.trace("datacenter={} license={}", dataCenter.id(), license);
                        if (license.isExpired())
                            throw new InvalidLicenseException("License '"+license.getId()+"' has expired");

                        return Optional.ofNullable(license);
                    } catch(AuthenticationException | NoHostAvailableException e) {
                        LOGGER.warn("datacenter="+dataCenter.id()+" Unable to get the Elassandra License", e);
                        return Optional.empty();
                    }
                }).ignoreElement();
    }
}
