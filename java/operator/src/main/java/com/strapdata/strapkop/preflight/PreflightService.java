/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

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
    private volatile boolean executed = false;

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

        executed = true;
        eventPublisher.publishEvent(new PreflightCompletedEvent());
    }

    public boolean isExecuted() {
        return executed;
    }
}
