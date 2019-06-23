package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.handler.*;
import com.strapdata.strapkop.pipeline.DataCenterPipeline;
import com.strapdata.strapkop.pipeline.EventPipeline;
import com.strapdata.strapkop.pipeline.NodeStatusPipeline;
import com.strapdata.strapkop.pipeline.StatefulsetPipeline;
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
        bind(NodeStatusPipeline.class, NodeStatusHandler.class);
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private <DataT> void bind(Class<? extends EventPipeline<DataT>> pipeline,
                                    Class<? extends Observer<DataT>> handler) {
        pipelines.get(pipeline).subscribe(handlers.get(handler));
    }
    
}
