package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import jmx.org.apache.cassandra.service.StorageServiceMBean;

@Controller("/operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationController {
    private final StorageServiceMBean storageServiceMBean;

    public OperationController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Post("/decommission")
    public Completable decommissionNode() {
        return Completable.fromCallable( () -> {
            storageServiceMBean.decommission();
            return null;
        }).subscribeOn(Schedulers.io());
    }
}
