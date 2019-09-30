package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.DataCenter;

@FunctionalInterface
public interface PostCreateHandler {
    void postCreate(DataCenter dataCenter, final Session session) throws Exception;
}
