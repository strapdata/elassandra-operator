package com.strapdata.strapkop.sidecar.services;


import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceStartedEvent;

import javax.inject.Singleton;

/**
 * Startup hook
 */
@Singleton
public class HookService implements ApplicationEventListener<ServiceStartedEvent> {

    // trigger hook on startup
    public void onApplicationEvent(final ServiceStartedEvent event) {

    }

}

