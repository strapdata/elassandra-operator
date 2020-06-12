package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.DataCenterStatusCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;
import com.strapdata.strapkop.model.k8s.datacenter.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import com.strapdata.strapkop.reconcilier.Reconciliable;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
    private final DataCenterStatusCache dataCenterStatusCache;

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "datacenter"));

    public DataCenterHandler(final WorkQueues workQueue,
                             final DataCenterController dataCenterController,
                             final DataCenterCache dataCenterCache,
                             final DataCenterStatusCache dataCenterStatusCache,
                             final MeterRegistry meterRegistry) {
        this.workQueues = workQueue;
        this.dataCenterController = dataCenterController;
        this.dataCenterCache = dataCenterCache;
        this.dataCenterStatusCache = dataCenterStatusCache;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("k8s.managed", tags, managed);
    }

    DataCenterStatus updateCaches(DataCenter dataCenter) {
        Key key = new Key(dataCenter.getMetadata());
        dataCenterCache.put(key, dataCenter);
        dataCenterStatusCache.putIfAbsent(key, dataCenter.getStatus());
        DataCenterStatus dataCenterStatus = dataCenterStatusCache.get(key);
        dataCenter.setStatus(dataCenterStatus); // overwrite the watched dc status by the one we have updated more recently.
        return dataCenterStatus;
    }

    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        logger.debug("event type={} name={} generation={} resourceVersion",
                event.getType(), event.getResource().getMetadata().getName(),
                event.getResource().getMetadata().getGeneration(),
                event.getResource().getMetadata().getResourceVersion());
        logger.trace("event={}", event);

        DataCenter dataCenter;
        switch (event.getType()) {
            case INITIAL:
                // trigger a dc reconcile on operator start.
                dataCenter = event.getResource();
                updateCaches(dataCenter);
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER, event.getType(),
                        dataCenterController.initDatacenter(dataCenter, new Operation()
                                .withSubmitDate(new Date())
                                .withTriggeredBy("Datacenter init"))
                                .doOnComplete(() -> {
                                    managed++;
                                })
                                .doFinally(() -> meterRegistry.counter("k8s.event.init", tags).increment()));
                break;

            case ADDED: {
                dataCenter = event.getResource();
                updateCaches(dataCenter);
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER,
                        event.getType(),
                        dataCenterController.initDatacenter(dataCenter, new Operation()
                                .withSubmitDate(new Date())
                                .withTriggeredBy("Datacenter added"))
                                .doOnComplete(() -> { managed++; })
                                .doFinally(() -> meterRegistry.counter("k8s.event.added", tags).increment())
                );
            }
            break;

            case MODIFIED: {
                dataCenter = event.getResource();
                // read dc status from cache rather than from event because last write may be not yet available.
                DataCenterStatus dataCenterStatus = updateCaches(dataCenter);
                Long observedGeneration = dataCenterStatus.getObservedGeneration();
                if (observedGeneration == null || dataCenter.getMetadata().getGeneration() > observedGeneration) {
                    logger.debug("dataCenter={} spec new generation={}", dataCenter.id(), dataCenter.getMetadata().getGeneration());
                    workQueues.submit(new ClusterKey(event.getResource()),
                            dataCenter.getMetadata().getResourceVersion(),
                            Reconciliable.Kind.DATACENTER, event.getType(),
                            dataCenterController.updateDatacenter(
                                    dataCenter,
                                    new Operation()
                                            .withSubmitDate(new Date())
                                            .withTriggeredBy("Datacenter modified spec generation=" + dataCenter.getMetadata().getGeneration()))
                                    .doFinally(() -> meterRegistry.counter("k8s.event.modified", tags).increment())
                    );
                }
            }
            break;

            case DELETED: {
                dataCenter = event.getResource();
                workQueues.submit(new ClusterKey(event.getResource()),
                        dataCenter.getMetadata().getResourceVersion(),
                        Reconciliable.Kind.DATACENTER, event.getType(),
                        dataCenterController.deleteDatacenter(dataCenter)
                                .doOnComplete(() -> {
                                    managed--;
                                    final Key key = new Key(event.getResource().getMetadata());
                                    dataCenterCache.remove(key);
                                    dataCenterStatusCache.remove(key);
                                    workQueues.dispose(new ClusterKey(event.getResource()));
                                })
                                .doFinally(() -> meterRegistry.counter("k8s.event.deleted", tags).increment())
                );
            }
            break;

            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                throw new IllegalStateException("Datacenter error event");

            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
    }
}
