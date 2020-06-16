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

import com.strapdata.strapkop.handler.*;
import com.strapdata.strapkop.pipeline.*;
import io.reactivex.Observer;

import javax.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Singleton
@SuppressWarnings("rawtypes")
public class RegisterHandlers implements Preflight<Void> {
    
    private final Map<Class<? extends Observer>, Observer> handlers = new HashMap<>();
    private final Map<Class<? extends EventPipeline>, EventPipeline> pipelines = new HashMap<>();
    
    public RegisterHandlers(@Handler Collection<Observer> handlers, Collection<EventPipeline> pipelines) {
        handlers.forEach(controller -> this.handlers.put(controller.getClass(), controller));
        pipelines.forEach(pipeline -> this.pipelines.put(pipeline.getClass(), pipeline));
    }
    
    @Override
    public Void call() throws Exception {
        bind(DataCenterPipeline.class, DataCenterHandler.class);
        bind(StatefulsetPipeline.class, StatefulsetHandler.class);
        bind(DeploymentPipeline.class, DeploymentHandler.class);
        bind(NodePipeline.class, NodeHandler.class);
        bind(TaskPipeline.class, TaskHandler.class);
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private <DataT> void bind(Class<? extends EventPipeline<? extends DataT>> pipeline,
                                    Class<? extends Observer<? extends DataT>> handler) {
        pipelines.get(pipeline).subscribe(handlers.get(handler));
    }
    
}
