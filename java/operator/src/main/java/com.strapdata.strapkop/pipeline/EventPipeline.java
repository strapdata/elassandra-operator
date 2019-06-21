package com.strapdata.strapkop.pipeline;


import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A generic reactive Pipeline to process events from various sources and trigger actions such as reconciliations.
 *
 * Event are grouped and deduplicated by using a key.
 *
 * Pipelines holds a cache of event data that is publicly readable. (get the latest value for a given key)
 *
 * @param <KeyT> the grouping key for events
 * @param <DataT> the event payload data
 */
public class EventPipeline<KeyT, DataT> {
    private final Logger logger = LoggerFactory.getLogger(EventPipeline.class);
    
    private EventSource<KeyT, DataT> source;
    private EventCache<KeyT, DataT> cache;
    private ConnectableObservable<DataT> observable = null;
    private List<Observer<DataT>> subscribers = new ArrayList<>();
    
    /**
     * Creating a new Pipeline is a simple as giving an EventSource to it.
     * @param source
     */
    public EventPipeline(final EventSource<KeyT, DataT> source) {
        this.source = source;
        this.cache = new EventCache<>();
    }
    
    /**
     * Create the actual rx observable and stat emitting events (on the io scheduler).
     * @throws Exception
     */
    public void start() throws Exception {
        logger.info("starting pipeline {} from thread {}", this.getClass().getName(), Thread.currentThread().getName());
        
        observable = source.createObservable()
                .doOnError(throwable -> {
                    logger.info("error in pipeline {}, retrying...", this.getClass().getSimpleName());
                    throwable.printStackTrace();
                })
                .retry()
                .doOnNext(e -> logger.info("{} received event {} in thread {}", this.getClass().getSimpleName(), e.getKey(), Thread.currentThread().getName()))
                .doOnNext(e -> cache.insert(e))
                .map(Event::getKey)
                .filter(k -> !cache.isProcessed(k))
                .map(k -> cache.get(k))
                .doOnNext(k -> logger.info("{} dispatching event {} to subscribers in thread {}", this.getClass().getSimpleName(), k, Thread.currentThread().getName()))
                .subscribeOn(Schedulers.io())
                .publish();

        // subscribe controllers
        subscribers.forEach(s -> observable.subscribe(s));
        
        // subscribe in-house error handler
        observable.subscribe(new ErrorHandlerSubscriber());

        // start emitting event
        observable.connect();
    }
    
    /**
     * Register a controller to the pipeline
     * @param controller a controller or any object that implements the Subscriber interface
     */
    public void subscribe(Observer<DataT> controller) {
        subscribers.add(controller);
    }
    
    /**
     * This method allows direct access to the pipeline cache
     *
     * @param key the key of the resource
     * @return an optional resource
     */
    public Optional<DataT> getFromCache(KeyT key) {
        try {
            return Optional.of(cache.get(key));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
    
    private class ErrorHandlerSubscriber implements Observer<DataT> {
        
        @Override
        public void onError(Throwable e) {
            logger.error("pipeline {} errored with {}", EventPipeline.this.getClass().getSimpleName(), e);
            e.printStackTrace();
        }
    
        @Override
        public void onComplete() {
            logger.error("pipeline {} completed", EventPipeline.this.getClass().getSimpleName());
        }
    
        @Override
        public void onSubscribe(Disposable d) {
        
        }
    
        @Override
        public void onNext(DataT key) {
        }
    }
}
