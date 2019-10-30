package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;

@FunctionalInterface
public interface CqlSessionSupplier {
    Single<Session> getSession(DataCenter dataCenter) throws Exception;
}
