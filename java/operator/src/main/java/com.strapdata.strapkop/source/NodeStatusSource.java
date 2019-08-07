package com.strapdata.strapkop.source;

import com.strapdata.model.Key;
import com.strapdata.model.sidecar.NodeStatus;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.NodeStatusCache;
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
public class NodeStatusSource implements EventSource<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(NodeStatusSource.class);
    
    
    private final NodeStatusCache nodeStatusCache;
    private final DataCenterCache dataCenterCache;
    private final SidecarClientFactory sidecarClientFactory;
    
    public NodeStatusSource(NodeStatusCache nodeStatusCache, DataCenterCache dataCenterCache, SidecarClientFactory sidecarClientFactory) {
        this.nodeStatusCache = nodeStatusCache;
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
                                return sidecarClientFactory.clientForHost(event.getPod().getFqdn()).status()
                                        .observeOn(Schedulers.io())
                                        .doOnSubscribe(d -> logger.debug("requesting pod {} sidecar for health check on thread {}", event.getPod().getName(), Thread.currentThread().getName()))
                                        .map(event::setCurrentMode)
                                        .doOnError(throwable -> logger.warn("failed to get the status from sidecar pod {}", event.getPod().getName(), throwable))
                                        .onErrorReturn(throwable -> event.setCurrentMode(NodeStatus.UNKNOWN));
                            } catch (Exception e) {
                                logger.warn("failed to get the status of pod={}", event.getPod().getName(), e);
                                return Single.just(event.setCurrentMode(NodeStatus.UNKNOWN));
                            }
                        }
                )
                .map(event -> event.setPreviousMode(nodeStatusCache.get(new Key(event.getPod().getName(), event.getPod().getNamespace()))))
                .doOnNext(event -> nodeStatusCache.put(new Key(event.getPod().getName(), event.getPod().getNamespace()), event.getCurrentMode()))
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }
}
