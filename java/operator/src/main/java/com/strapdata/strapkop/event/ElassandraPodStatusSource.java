package com.strapdata.strapkop.event;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.sidecar.JmxmpElassandraProxy;
import io.micronaut.scheduling.executor.ExecutorFactory;
import io.micronaut.scheduling.executor.UserExecutorConfiguration;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Singleton
public class ElassandraPodStatusSource implements EventSource<NodeStatusEvent> {

    private final Logger logger = LoggerFactory.getLogger(ElassandraPodStatusSource.class);


    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final JmxmpElassandraProxy jmxmpElassandraProxy;
    private OperatorConfig config;
    private Scheduler statusScheduler;

    public ElassandraPodStatusSource(ElassandraNodeStatusCache elassandraNodeStatusCache,
                                     JmxmpElassandraProxy jmxmpElassandraProxy,
                                     OperatorConfig config,
                                     ExecutorFactory executorFactory,
                                     @Named("status") UserExecutorConfiguration userExecutorConfiguration) {
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.jmxmpElassandraProxy = jmxmpElassandraProxy;
        this.config = config;
        this.statusScheduler = Schedulers.from(executorFactory.executorService(userExecutorConfiguration));
    }

    @Override
    public Observable<NodeStatusEvent> createObservable() {
        return Observable.interval(config.getElassandraNodeWatchPeriodInSec(), TimeUnit.SECONDS)
                .subscribeOn(statusScheduler)   // which threads are going to emit values
                .observeOn(statusScheduler)     // which threads are going to handle and observe values
                .flatMap(i -> {
                    logger.debug("elassandraNodeStatusCache={}", elassandraNodeStatusCache);
                    return Observable.fromIterable(elassandraNodeStatusCache.keySet());
                })
                .flatMapSingle(pod -> {
                            NodeStatusEvent event = new NodeStatusEvent().setPod(pod);
                            logger.debug("event={}", event);
                            return jmxmpElassandraProxy.status(event.getPod())
                                    .observeOn(Schedulers.io())
                                    .map(nodeStatus -> {
                                        logger.debug("pod={} Cassandra status={}", event.getPod().id(), nodeStatus);
                                        event.setCurrentMode(nodeStatus);
                                        return event;
                                    })
                                    .onErrorResumeNext(throwable -> {
                                        logger.debug("pod="+event.getPod().id()+" Failed to get Cassandra status:", throwable.toString());
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
                            logger.debug("pod{} previous={} ignored", event.getPod().id(), event.getPreviousMode());
                            break;
                        default:
                            logger.debug("pod{} previous={} current={} next={}", event.getPod().id(), event.getPreviousMode(), event.getCurrentMode(), event.getCurrentMode());
                            elassandraNodeStatusCache.put(event.getPod(), event.getCurrentMode());
                    }
                    return event;
                })
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }

}
