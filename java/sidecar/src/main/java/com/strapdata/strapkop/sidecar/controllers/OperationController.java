package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.swagger.v3.oas.annotations.tags.Tag;
import jmx.org.apache.cassandra.service.StorageServiceMBean;

import java.util.List;

/**
 * Run a cassandra operation.
 */
@Tag(name = "operations")
@Controller("/operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationController {
    private final StorageServiceMBean storageServiceMBean;

    public OperationController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Post("/decommission")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> decommissionNode() {
        return Single.fromCallable( () -> {
            storageServiceMBean.decommission();
            return "OK";
        }).subscribeOn(Schedulers.io());
    }
    
    
    @Post("/cleanup")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> cleanupNode() {
        return Single.fromCallable( () -> {
            final List<String> keyspaces = storageServiceMBean.getNonLocalStrategyKeyspaces();
            for (String ks : keyspaces) {
                storageServiceMBean.forceKeyspaceCleanup(2, ks);
            }
            return "OK";
        }).subscribeOn(Schedulers.io());
    }
}
