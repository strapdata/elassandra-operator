package com.strapdata.strapkop.sidecar.controllers;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.swagger.v3.oas.annotations.tags.Tag;
import jmx.org.apache.cassandra.locator.EndpointSnitchInfoMBean;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import java.util.*;

/**
 * Run a cassandra operation.
 */
@Tag(name = "operations")
@Controller("/operations")
@Produces(MediaType.APPLICATION_JSON)
public class OperationController {
    private final StorageServiceMBean storageServiceMBean;
    private final EndpointSnitchInfoMBean endpointSnitchInfoMBean;

    private static final Logger logger = LoggerFactory.getLogger(OperationController.class);

    public OperationController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
        this.endpointSnitchInfoMBean = cassandraModule.endpointSnitchInfoMBean();
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

    @Post("/list/{dcName}")
    public Single<Set<String>> listNodes(@NotBlank @QueryValue("dcName") String dcName) {
        logger.debug("listNodes dcName={}", dcName);
        return Single.fromCallable( () -> {
            Map<String, String> tokensToEndpoints = storageServiceMBean.getTokenToEndpointMap();
            Set<String> hostIds = new HashSet<>();
            for (Map.Entry<String, String> tokenAndEndPoint : tokensToEndpoints.entrySet()) {
                String dc = endpointSnitchInfoMBean.getDatacenter(tokenAndEndPoint.getValue());
                if (dcName.equals(dc))
                    hostIds.add(tokenAndEndPoint.getValue());
            }
            return hostIds;
        }).subscribeOn(Schedulers.io());
    }

    @Post("/remove/{hostId}")
    public Single<Set<String>> listNodes(@NotBlank @QueryValue("dcName") String dcName) {
        logger.debug("listNodes dcName={}", dcName);
        return Single.fromCallable( () -> {
            Map<String, String> tokensToEndpoints = storageServiceMBean.getTokenToEndpointMap();
            Set<String> hostIds = new HashSet<>();
            for (Map.Entry<String, String> tokenAndEndPoint : tokensToEndpoints.entrySet()) {
                String dc = endpointSnitchInfoMBean.getDatacenter(tokenAndEndPoint.getValue());
                if (dcName.equals(dc))
                    hostIds.add(tokenAndEndPoint.getValue());
            }
            return hostIds;
        }).subscribeOn(Schedulers.io());
    }
    
    @Post("/cleanup")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> cleanup(@Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("cleanup keyspace={}", keyspace);
        return Single.fromCallable( () -> {
            final List<String> keyspaces = keyspace == null ? storageServiceMBean.getNonLocalStrategyKeyspaces() : ImmutableList.of(keyspace);
            for (String ks : keyspaces) {
                storageServiceMBean.forceKeyspaceCleanup(2, ks);
                logger.info("Cleanup done for keyspace={}", ks);
            }
            return "OK";
        }).subscribeOn(Schedulers.io());
    }

    @Post("/repair")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<String> repair(@Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("repair keyspace={}", keyspace);
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

    /**
     * Datacenter rebuild
     * @param keyspace
     * @return
     */
    @Post("/flush")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<HttpStatus> flush(@Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("flush keyspace={}", keyspace);
        return Single.fromCallable( () -> {
            List<String> keyspaceList = (keyspace == null) ? storageServiceMBean.getKeyspaces() : ImmutableList.of(keyspace);
            logger.info("Flush requested for keyspaces={}", keyspaceList);
            for(String ks : keyspaceList)
                storageServiceMBean.forceKeyspaceFlush(ks);
            logger.info("Flush done for keyspaces={}", keyspaceList);
            return HttpStatus.OK;
        }).subscribeOn(Schedulers.io());
    }

    /**
     * Datacenter rebuild
     * @param keyspace
     * @return
     */
    @Post("/rebuild/{srcDcName}")
    @Produces(MediaType.TEXT_PLAIN)
    public Single<HttpStatus> rebuild(@QueryValue("srcDcName") String srcDcName, @Nullable @QueryValue("keyspace") String keyspace) {
        logger.debug("rebuild srcDcName={} keyspace={}", srcDcName, keyspace);
        return Single.fromCallable( () -> {
            logger.info("Rebuilding from dc={} requested for keyspace={}", srcDcName, keyspace);
            storageServiceMBean.rebuild(srcDcName, keyspace, null, null);
            logger.info("Rebuilt from dc={} requested for keyspace={}", srcDcName, keyspace);
            return HttpStatus.OK;
        }).subscribeOn(Schedulers.io());
    }
}
