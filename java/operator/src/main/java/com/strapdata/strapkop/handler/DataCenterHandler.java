package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
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
        meterRegistry.gauge("k8s.managed", tags, managed);
    }

    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        DataCenter dataCenter;
        logger.debug("DataCenter event={}", event);

        Completable completable = null;
        switch (event.getType()) {
            case INITIAL:
            case ADDED:
                dataCenter = event.getResource();

                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenterController.initDatacenter(new Operation().withSubmitDate(new Date()).withDesc("dc-added"), dataCenter)
                                .doOnComplete(() -> {
                                    managed++;
                                })
                                .doFinally(() -> {
                                    meterRegistry.counter("k8s.event.added", tags).increment();
                                })
                );
                break;
            case MODIFIED:
                dataCenter = event.getResource();
                Operation op = new Operation().withSubmitDate(new Date()).withDesc("dc-modified");
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenterController.updateDatacenter(new Operation().withSubmitDate(new Date()).withDesc("dc-modified"), dataCenter)
                                .doFinally(() -> {
                                    meterRegistry.counter("k8s.event.modified", tags).increment();
                                })
                );
                break;
            case DELETED:
                dataCenter = event.getResource();
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenterController.deleteDatacenter(dataCenter)
                                .doOnComplete(() -> {
                                    managed--;
                                    final Key key = new Key(event.getResource().getMetadata());
                                    dataCenterCache.remove(key);
                                    workQueues.dispose(new ClusterKey(event.getResource()));
                                })
                                .doFinally(() -> {
                                    meterRegistry.counter("k8s.event.deleted", tags).increment();
                                })
                );
                break;
            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("Datacenter error event");
            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
    }
}
