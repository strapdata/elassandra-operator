package com.strapdata.strapkop.pipeline;

import com.strapdata.model.k8s.task.Task;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.source.TaskEventSource;
import io.micronaut.context.annotation.Infrastructure;
import javax.inject.Singleton;

@Singleton
@Infrastructure
public class TaskPipeline extends EventPipeline<K8sWatchEvent<Task<?, ?>>> {
    
    public TaskPipeline(final TaskEventSource taskEventSource) {
        super(taskEventSource);
    }
}
