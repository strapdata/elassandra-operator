package com.strapdata.strapkop.event;

import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Singleton
public class ElassandraPodStatusSource implements EventSource<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(ElassandraPodStatusSource.class);
    
    
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final DataCenterCache dataCenterCache;
    private final SidecarClientFactory sidecarClientFactory;
    private OperatorConfig config;

    public ElassandraPodStatusSource(ElassandraNodeStatusCache elassandraNodeStatusCache, DataCenterCache dataCenterCache, SidecarClientFactory sidecarClientFactory, OperatorConfig config) {
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.dataCenterCache = dataCenterCache;
        this.sidecarClientFactory = sidecarClientFactory;
        this.config = config;
    }
    
    @Override
    public Observable<NodeStatusEvent> createObservable() {
        return Observable.interval(config.getElassandraNodeWatchPeriodInSec(), TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .map(i -> { logger.debug("run node status health check on thread {}", Thread.currentThread().getName()); return i; })
                .flatMap(i -> Observable.fromIterable(dataCenterCache.listPods()))
                .map(pod -> new NodeStatusEvent().setPod(pod))
                .flatMapSingle(event -> {
                            try {
                                return sidecarClientFactory.clientForPod(event.getPod()).status()
                                        .observeOn(Schedulers.io())
                                        .map(nodeStatus -> {
                                            logger.debug("requesting pod={} sidecar for health check={} on thread {}", event.getPod().getName(), nodeStatus, Thread.currentThread().getName());
                                            event.setCurrentMode(nodeStatus);
                                            return event;
                                        })
                                        .onErrorReturn(throwable -> {
                                            logger.debug("failed to get the status from sidecar pod=" + event.getPod().getName(), throwable.getMessage());
                                            sidecarClientFactory.invalidateClient(event.getPod());
                                            event.setCurrentMode(ElassandraNodeStatus.UNKNOWN);
                                            return event;
                                        });
                            } catch (Exception e) {
                                logger.warn("failed to get the status of pod=" + event.getPod().getName(), e);
                                sidecarClientFactory.invalidateClient(event.getPod());
                                return Single.just(event).map(v -> { v.setCurrentMode(ElassandraNodeStatus.UNKNOWN); return v;});
                            }
                        }
                )
                .map(event -> {
                    ElassandraNodeStatus previousStatus = elassandraNodeStatusCache.getOrDefault(event.getPod(), ElassandraNodeStatus.UNKNOWN);
                    event.setPreviousMode(previousStatus);
                    switch (previousStatus) {
                        case DOWN:
                        case DECOMMISSIONED:
                        case DRAINED:
                            // do not change the status if the current one is UNKNOWN to allow resource releasing
                            // in other case the Node is coming back and the status must be updated
                            if (!ElassandraNodeStatus.UNKNOWN.equals(event.getCurrentMode())) {
                                logger.debug("caching {}={} previous={}", event.getPod(), event.getCurrentMode(),  event.getPreviousMode());
                                elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode());
                            } else {
                                logger.debug("ignore caching {}={} previous={}", event.getPod(), event.getCurrentMode(),  event.getPreviousMode());
                            }
                            break;
                        default:
                            logger.debug("caching {}={} previous={}", event.getPod(), event.getCurrentMode(),  event.getPreviousMode());
                            elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode());
                    }
                    return event;
                })
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }
    
}
