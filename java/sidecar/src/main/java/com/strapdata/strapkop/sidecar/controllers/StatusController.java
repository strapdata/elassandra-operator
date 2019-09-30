package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.swagger.v3.oas.annotations.tags.Tag;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Get Cassandra node status
 */
@Tag(name = "status")
@Controller("/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusController {
    
    private static final Logger logger = LoggerFactory.getLogger(StatusController.class);
    
    private final StorageServiceMBean storageServiceMBean;

    public StatusController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Get("/")
    public ElassandraNodeStatus getStatus() {
        try {
            return ElassandraNodeStatus.valueOf(storageServiceMBean.getOperationMode());
        }
        catch (RuntimeException e) {
            logger.error("error while getting operation mode", e);
            return ElassandraNodeStatus.UNKNOWN;
        }
    }
}
