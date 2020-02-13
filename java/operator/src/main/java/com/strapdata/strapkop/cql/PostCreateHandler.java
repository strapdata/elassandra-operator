package com.strapdata.strapkop.cql;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;


@FunctionalInterface
public interface PostCreateHandler {
    void postCreate(DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) throws Exception;
}
