package com.strapdata.strapkop.pipelines;

import com.strapdata.strapkop.preflights.PreflightService;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;

import javax.inject.Singleton;
import java.util.List;

/**
 * Wait for preflight to complete before starting kubernetes watches
 */
@Singleton
public class PipelinesStarter {
    
    private final List<EventPipeline<?>> pipelines;
    
    public PipelinesStarter(List<EventPipeline<?>> pipelines) {
        this.pipelines = pipelines;
    }
    
    @EventListener
    @Async
    public void onPreflightCompleted(PreflightService.PreflightCompletedEvent event) {
        for (EventPipeline<?> eventPipeline : pipelines) {
            eventPipeline.start();
        }
    }
}
