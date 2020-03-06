package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterDeleteReconcilier;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueues workQueues;
    private final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;
    private final DataCenterDeleteReconcilier dataCenterDeleteReconcilier;
    private final MeterRegistry meterRegistry;
    private final DataCenterCache dataCenterCache;

    private int dataCenterCacheMaxSize = 0;

    public DataCenterHandler(final WorkQueues workQueue,
                             final DataCenterUpdateReconcilier dataCenterUpdateReconcilier,
                             final DataCenterDeleteReconcilier dataCenterDeleteReconcilier,
                             final DataCenterCache dataCenterCache,
                             final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.dataCenterUpdateReconcilier = dataCenterUpdateReconcilier;
        this.dataCenterDeleteReconcilier = dataCenterDeleteReconcilier;
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
        if (event.isUpdate()) {
            completable = dataCenterUpdateReconcilier.reconcile(key);
        }
        else if (event.isDeletion()) {
            completable = dataCenterDeleteReconcilier.reconcile(dataCenter)
                    .doOnComplete(new Action() {
                        @Override
                        public void run() throws Exception {
                            dataCenterCache.remove(key);
                            meterRegistry.gauge("datacenter_cache.current", dataCenterCache.size());
                        }
                    });
        }
        if (completable != null)
            workQueues.submit(new ClusterKey(event.getResource()), completable);
    }
}
