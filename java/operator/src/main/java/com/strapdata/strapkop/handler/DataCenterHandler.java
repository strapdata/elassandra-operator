package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.reactivex.Completable;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueues workQueues;
    private final DataCenterController dataCenterController;
    private final MeterRegistry meterRegistry;
    private final DataCenterCache dataCenterCache;

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "datacenter"));

    public DataCenterHandler(final WorkQueues workQueue,
                             final DataCenterController dataCenterController,
                             final DataCenterCache dataCenterCache,
                             final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("k8s.managed",  tags, managed);
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        DataCenter dataCenter;
        logger.debug("DataCenter event={}", event);

        Completable completable = null;
        switch(event.getType()) {
            case INITIAL:
            case ADDED:
                dataCenter = event.getResource();
                completable = dataCenterController.initDatacenter(dataCenter);
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed++;
                break;
            case MODIFIED:
                meterRegistry.counter("k8s.event.modified", tags).increment();
                dataCenter = event.getResource();
                completable = dataCenterController.updateDatacenter(dataCenter);
                break;
            case DELETED:
                dataCenter = event.getResource();
                completable = dataCenterController.deleteDatacenter(dataCenter)
                        .doOnComplete(new Action() {
                            @Override
                            public void run() throws Exception {
                                final Key key = new Key(event.getResource().getMetadata());
                                dataCenterCache.remove(key);
                            }
                        });
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed--;
                break;
            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("Datacenter error event");
            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
        if (completable != null)
            workQueues.submit(new ClusterKey(event.getResource()), completable);
    }
}
