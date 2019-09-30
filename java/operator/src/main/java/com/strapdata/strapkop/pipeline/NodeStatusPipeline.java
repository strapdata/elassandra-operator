package com.strapdata.strapkop.pipeline;

import com.strapdata.strapkop.event.NodeStatusEvent;
import com.strapdata.strapkop.event.ElassandraPodStatusSource;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;

/**
 * Elassandra node status pipeline
 */
@Context
@Infrastructure
public class NodeStatusPipeline extends EventPipeline<NodeStatusEvent> {
    public NodeStatusPipeline(ElassandraPodStatusSource source) {
        super(source);
    }
}
