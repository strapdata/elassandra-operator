package com.strapdata.strapkop.event;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Singleton
public class ElassandraPodStatusSource implements EventSource<NodeStatusEvent> {

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodStatusSource.class);


    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final DataCenterCache dataCenterCache;
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private OperatorConfig config;

    public ElassandraPodStatusSource(ElassandraNodeStatusCache elassandraNodeStatusCache, DataCenterCache dataCenterCache, JmxmpElassandraProxy jmxmpElassandraProxy, OperatorConfig config) {
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.dataCenterCache = dataCenterCache;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.config = config;
    }

    @Override
    public Observable<NodeStatusEvent> createObservable() {
        return Observable.interval(config.getElassandraNodeWatchPeriodInSec(), TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .flatMap(i -> {
                    List<ElassandraPod> pods = dataCenterCache.listPods();
                    logger.debug("/{}", pods);
                    return Observable.fromIterable(pods);
                })
                .map(pod -> new NodeStatusEvent().setPod(pod))
                .flatMapSingle(event -> {
                            logger.debug("event={}", event);
                            return jmxmpElassandraProxy.status(event.getPod())
                                    .observeOn(Schedulers.io())
                                    .map(nodeStatus -> {
                                        logger.debug("requesting pod={} sidecar for health check={}", event.getPod().getName(), nodeStatus);
                                        event.setCurrentMode(nodeStatus);
                                        return event;
                                    })
                                    .onErrorResumeNext(throwable -> {
                                        logger.debug("failed to get the status from pod=" + event.getPod().getName() + ":", throwable.toString());
                                        jmxmpElassandraProxy.invalidateClient(event.getPod(), throwable);
                                        event.setCurrentMode(ElassandraNodeStatus.UNKNOWN);
                                        return Single.just(event);
                                    });
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
                                logger.debug("caching {}={} previous={}", event.getPod(), event.getCurrentMode(), event.getPreviousMode());
                                elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode());
                            } else {
                                logger.debug("ignore caching {}={} previous={}", event.getPod(), event.getCurrentMode(), event.getPreviousMode());
                            }
                            break;
                        default:
                            logger.debug("caching {}={} previous={}", event.getPod(), event.getCurrentMode(), event.getPreviousMode());
                            elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode());
                    }
                    return event;
                })
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }

}
