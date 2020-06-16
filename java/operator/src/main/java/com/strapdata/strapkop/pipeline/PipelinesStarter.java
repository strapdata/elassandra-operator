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

package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.preflight.PreflightService;
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
