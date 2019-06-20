package com.strapdata.strapkop.watch;

import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.strapdata.strapkop.CassandraHealthCheckService;
import com.strapdata.strapkop.pipeline.DataCenterPipeline;
import com.strapdata.strapkop.preflight.PreflightService;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * Wait for preflight to complete before starting kubernetes watches
 */
@Singleton
public class WatchServicesStarter {
    
    @Inject
    private List<WatchService<Object, Object>> watchServices;
    
    // TODO: K8s Watch and cassandra health check will all implement the same abstract class
    @Inject
    private CassandraHealthCheckService cassandraHealthCheckService;
    
    @Inject
    private DataCenterPipeline dataCenterPipeline;
    
    @EventListener
    @Async
    public void onPreflightCompleted(PreflightService.PreflightCompletedEvent event) throws Exception {
        // watchServices.forEach(AbstractExecutionThreadService::startAsync);
        // cassandraHealthCheckService.startAsync();
        dataCenterPipeline.start();
    }
}
