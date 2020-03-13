package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueues workQueues;
    private final DataCenterController dataCenterController;
    private final MeterRegistry meterRegistry;
    private final DataCenterCache dataCenterCache;

    private int dataCenterCacheMaxSize = 0;

    public DataCenterHandler(final WorkQueues workQueue,
                             final DataCenterController dataCenterController,
                             final DataCenterCache dataCenterCache,
                             final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        final DataCenter dataCenter = event.getResource();
        final Key key = new Key(event.getResource().getMetadata());
        logger.debug("DataCenter event type={} dc={}", event.getType(), dataCenter.id());

        if (dataCenterCache.putIfAbsent(key, dataCenter) == null) {
            this.dataCenterCacheMaxSize = Math.max(dataCenterCacheMaxSize, dataCenterCache.size());
            meterRegistry.gauge("datacenter_cache.max", dataCenterCacheMaxSize);
            meterRegistry.gauge("datacenter_cache.current", dataCenterCache.size());
        };

        Completable completable = null;
        switch(event.getType()) {
            case ADDED:
            case INITIAL:
                completable = dataCenterController.initDatacenter(dataCenter);
                break;
            case MODIFIED:
                completable = dataCenterController.updateDatacenter(dataCenter);
                break;
            case DELETED:
                completable = dataCenterController.deleteDatacenter(dataCenter)
                        .doOnComplete(new Action() {
                            @Override
                            public void run() throws Exception {
                                dataCenterCache.remove(key);
                                meterRegistry.gauge("datacenter_cache.current", dataCenterCache.size());
                            }
                        });
                break;
            case ERROR:
                throw new IllegalStateException("Datacenter error event");
            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
        if (completable != null)
            workQueues.submit(new ClusterKey(event.getResource()), completable);
    }
}
