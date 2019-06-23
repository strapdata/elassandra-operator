package com.strapdata.strapkop.preflight;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Collection;

/**
 * Creates CRD defintion and defaultCA
 */
@Singleton
public class PreflightService {

    static final Logger logger = LoggerFactory.getLogger(PreflightService.class);

    private final ApplicationEventPublisher eventPublisher;
    private final Collection<Preflight<?>> preflights;
    
    public PreflightService(ApplicationEventPublisher eventPublisher, Collection<Preflight<?>> preflights) {
        this.eventPublisher = eventPublisher;
        this.preflights = preflights;
    }
    
    public static class PreflightCompletedEvent {
    }
    
    @EventListener
    @Async
    void onStartup(ServiceStartedEvent event) {
        
        for (Preflight<?> preflight : preflights) {
    
            try {
                preflight.call();
            } catch (Exception e) {
                e.printStackTrace();
                // TODO: should we refuse to start here ?
            }
        }
        
        eventPublisher.publishEvent(new PreflightCompletedEvent());
    }
    
}
