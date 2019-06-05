package com.strapdata.strapkop.sidecar.controllers;

import com.instaclustr.model.sidecar.NodeStatus;
import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Single;
import jmx.org.apache.cassandra.service.StorageServiceMBean;

@Controller("/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusController {
    private final StorageServiceMBean storageServiceMBean;

    public StatusController(CassandraModule cassandraModule) {
        this.storageServiceMBean = cassandraModule.storageServiceMBeanProvider();
    }

    @Get("/")
    public Single<NodeStatus> getStatus() {
        return Single.create(emitter -> {
            emitter.onSuccess(NodeStatus.valueOf(storageServiceMBean.getOperationMode()));
        });
    }
}
