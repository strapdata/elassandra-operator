package com.strapdata.strapkop.cql;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import com.strapdata.elasticsearch.plugin.license.License;
import com.strapdata.elasticsearch.plugin.license.LicenseVerifierService;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.exception.InvalidLicenseException;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Singleton
public class CqlLicenseManager extends AbstractManager<License> implements LicenseVerifierService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CqlLicenseManager.class);
    public static final String SELECT_STATEMENT = "SELECT * from elastic_admin.licenses";

    public Completable verifyLicense(DataCenter dataCenter, CqlSessionHandler sessionHandler) {
        LOGGER.debug("Try to load the most recent License");
        try {
            return Completable.fromSingle(sessionHandler.getSession(dataCenter)
                    .map(CqlLicenseManager::selectLicenses)
                    .map( (rs) -> {
                        LOGGER.trace("verifying licences");
                        Stream<License> rowStream = StreamSupport.stream(rs.spliterator(), false).map(CqlLicense::fromRow);
                        License license = verifyLicenses(rowStream, dataCenter.getSpec().getClusterName(), dataCenter.getSpec().getDatacenterName());
                        return Optional.ofNullable(license);
                    })
                    .map(CqlLicenseManager::checkLicense));
        } catch (Exception e) {
            LOGGER.warn("Unable to verify the Elassandra License", e);
            throw new InvalidLicenseException("Unable to verify the Elassandra License due to " + e.getClass().getName());
        }
    }

    private static License checkLicense(Optional<License> optLicense) {
        License license = optLicense.orElseThrow(() -> new InvalidLicenseException("No valid license found"));
        if (license.isExpired()) {
            throw new InvalidLicenseException("License '"+license.getId()+"' has expired");
        } else {
            LOGGER.debug("Valid License found");
            return license;
        }
    }

    private static ResultSet selectLicenses(Session session) {
        LOGGER.trace("Execute '" + SELECT_STATEMENT + "' table");
        return session.execute(SELECT_STATEMENT);
    }
}
