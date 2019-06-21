package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.controllers.*;
import com.strapdata.strapkop.pipeline.DataCenterPipeline;
import com.strapdata.strapkop.pipeline.DataCenterReconciliationPipeline;
import com.strapdata.strapkop.pipeline.EventPipeline;
import com.strapdata.strapkop.pipeline.StatefulsetPipeline;
import io.reactivex.Observer;

import javax.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Singleton
@SuppressWarnings("rawtypes")
public class RegisterControllers implements Preflight<Void> {
    
    private final Map<Class<? extends Observer>, Observer> controllers = new HashMap<>();
    private final Map<Class<? extends EventPipeline>, EventPipeline> pipelines = new HashMap<>();
    
    public RegisterControllers(@PipelineController Collection<Observer> controllers, Collection<EventPipeline> pipelines) {
        controllers.forEach(controller -> this.controllers.put(controller.getClass(), controller));
        pipelines.forEach(pipeline -> this.pipelines.put(pipeline.getClass(), pipeline));
    }
    
    @Override
    public Void call() throws Exception {
        bind(DataCenterPipeline.class, DataCenterIntermediateController.class);
        bind(DataCenterPipeline.class, DataCenterDeletionController.class);
        bind(StatefulsetPipeline.class, StatefulsetIntermediateController.class);
        bind(DataCenterReconciliationPipeline.class, DataCenterReconciliationController.class);
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private <KeyT, DataT> void bind(Class<? extends EventPipeline<KeyT, DataT>> pipeline,
                                    Class<? extends Observer<DataT>> controller) {
        pipelines.get(pipeline).subscribe(controllers.get(controller));
    }
    
}
