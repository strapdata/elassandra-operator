package com.strapdata.strapkop.sidecar.controllers;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.reactivex.Single;
import io.reactivex.annotations.Nullable;
import io.reactivex.schedulers.Schedulers;
import io.swagger.v3.oas.annotations.tags.Tag;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Run a cassandra operation.
 */
@Tag(name = "operations")
@Controller("/operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationController {
    private final StorageServiceMBean storageServiceMBean;

    private static final Logger logger = LoggerFactory.getLogger(OperationController.class);

    public OperationController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Post("/decommission")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> decommissionNode() {
        logger.debug("Node decommission received");
        return Single.fromCallable( () -> {
            storageServiceMBean.decommission();
            logger.info("decommission requested");
            return "OK";
        }).subscribeOn(Schedulers.io());
    }
    
    
    @Post("/cleanup")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> cleanup(@Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("Node cleanup received");
        return Single.fromCallable( () -> {
            final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
            for (String ks : keyspaces) {
                storageServiceMBean.forceKeyspaceCleanup(2, ks);
                logger.info("Cleanup requested for keyspace={}", ks);
            }
            return "OK";
        }).subscribeOn(Schedulers.io());
    }

    @Post("/repair")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> repair(@Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("Node repair received");
        return Single.fromCallable( () -> {
            Map<String, String> options = new HashMap<>();
            options.put("incremental", Boolean.FALSE.toString());
            options.put("primaryRange", Boolean.TRUE.toString());
            final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
            for (String ks : keyspaces) {
                storageServiceMBean.repairAsync(ks, options);
                logger.info("Repair requested for keyspace={}", ks);
            }
            return "OK";
        }).subscribeOn(Schedulers.io());
    }
}
