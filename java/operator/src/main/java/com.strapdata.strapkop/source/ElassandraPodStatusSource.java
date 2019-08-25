package com.strapdata.strapkop.source;

import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.event.NodeStatusEvent;
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
    
    public ElassandraPodStatusSource(ElassandraNodeStatusCache elassandraNodeStatusCache, DataCenterCache dataCenterCache, SidecarClientFactory sidecarClientFactory) {
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.dataCenterCache = dataCenterCache;
        this.sidecarClientFactory = sidecarClientFactory;
    }
    
    @Override
    public Observable<NodeStatusEvent> createObservable() {
        return Observable.interval(10, TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .doOnNext(i -> logger.debug("run node status health check on thread {}", Thread.currentThread().getName()))
                .flatMap(i -> Observable.fromIterable(dataCenterCache.listPods()))
                .map(pod -> new NodeStatusEvent()
                        .setPod(pod)
                )
                .flatMapSingle(event -> {
                            try {
                                return sidecarClientFactory.clientForPod(event.getPod()).status()
                                        .observeOn(Schedulers.io())
                                        .doOnSubscribe(d -> logger.debug("requesting pod {} sidecar for health check on thread {}", event.getPod().getName(), Thread.currentThread().getName()))
                                        .map(event::setCurrentMode)
                                        .doOnError(throwable -> {
                                            logger.warn("failed to get the status from sidecar pod {}", event.getPod().getName(), throwable);
                                            sidecarClientFactory.invalidateClient(event.getPod());
                                        })
                                        .onErrorReturn(throwable -> event.setCurrentMode(ElassandraNodeStatus.UNKNOWN));
                            } catch (Exception e) {
                                logger.warn("failed to get the status of pod={}", event.getPod().getName(), e);
                                sidecarClientFactory.invalidateClient(event.getPod());
                                return Single.just(event.setCurrentMode(ElassandraNodeStatus.UNKNOWN));
                            }
                        }
                )
                .map(event -> event.setPreviousMode(elassandraNodeStatusCache.get(event.getPod())))
                .doOnNext(event -> elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode()))
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }
    
}
