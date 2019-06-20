package com.strapdata.strapkop.pipeline;


import com.strapdata.strapkop.controllers.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.observables.ConnectableObservable;
import rx.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;

public class EventPipeline<KeyT, DataT> {
    private final Logger logger = LoggerFactory.getLogger(EventPipeline.class);
    
    private EventSource<KeyT, DataT> source;
    private EventCache<KeyT, DataT> cache;
    private ConnectableObservable<KeyT> observable = null;
    private List<Controller<DataT>> subscribers = new ArrayList<>();
    
    
    public EventPipeline(final EventSource<KeyT, DataT> source) {
        this.source = source;
        this.cache = new EventCache<>();
    }
    
    public void start() throws Exception {
        logger.info("starting pipeline {} from thread {}", this.getClass().getName(), Thread.currentThread().getName());
        observable = source.createObservable()
                .doOnNext(e -> logger.info("{} received event {} in thread {}", this.getClass().getSimpleName(), e.getKey(), Thread.currentThread().getName()))
                .doOnNext(e -> cache.insert(e))
                .map(Event::getKey)
                .filter(k -> !cache.isProcessed(k))
                .doOnNext(k -> logger.info("{} dispatching event {} to subscribers in thread {}", this.getClass().getSimpleName(), k, Thread.currentThread().getName()))
                .subscribeOn(Schedulers.io())
                .publish();
        subscribers.forEach(s -> observable.subscribe(key -> {
            try {
                s.accept(cache.get(key));
            } catch (Exception e) {
                e.printStackTrace();
                // TODO: what to do if a controller throws an exception ?
            }
        }));
        observable.doOnCompleted(() -> logger.error("pipeline {} completed", this.getClass().getSimpleName()));
        observable.doOnError(throwable -> logger.error("pipeline {} errored with {}", this.getClass().getSimpleName(), throwable));
        observable.connect();
    }
    
    public void subscribe(Controller<DataT> controller) {
        subscribers.add(controller);
    }
}
