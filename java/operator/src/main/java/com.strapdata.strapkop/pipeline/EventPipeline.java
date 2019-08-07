package com.strapdata.strapkop.pipeline;


import com.google.common.collect.ImmutableSet;
import com.strapdata.strapkop.source.EventSource;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.observables.ConnectableObservable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A generic reactive Pipeline to reconcile events from various sources and trigger actions such as reconciliations.
 * @param <DataT> the event payload data
 */
public class EventPipeline<DataT> {
    
    private final Logger logger = LoggerFactory.getLogger(EventPipeline.class);
    
    private EventSource<DataT> source;
    private ConnectableObservable<DataT> observable = null;
    private List<Observer<DataT>> subscribers = new ArrayList<>();
    
    /**
     * Creating a new Pipeline is a simple as giving an EventSource to it.
     *
     * @param source
     */
    public EventPipeline(final EventSource<DataT> source) {
        this.source = source;
        //this.cache = new EventCache<>();
    }

    // we silent exceptions that are periodically triggered by k8s watch
    private static final Set<Class<?>> skippedExceptions = ImmutableSet.of(SocketTimeoutException.class);
    
    /**
     * Create the actual rx observable and start emitting events (on the io scheduler).
     * The Observable will automatically be recreated and resubscribed each time onComplete or onError is invoked.
     */
    public void start() {
        logger.info("starting pipeline {} from thread {}", this.getClass().getName(), Thread.currentThread().getName());
        
        // the defer operator combined with retry and repeat is used to recreate the observable after each complete or error.
        final Observable<DataT> coldObservable = Observable.defer(() -> source.createObservable())
                .observeOn(Schedulers.io())
                .doOnError(throwable -> {
                    if (!(throwable instanceof RuntimeException) || !skippedExceptions.contains(throwable.getCause().getClass())) {
                        logger.debug("error in pipeline {}, recreating the observable in 1 second", this.getClass().getSimpleName(), throwable);
                    }
                })
                .doOnComplete(() -> logger.debug("pipeline {} completed, recreating observable in 1 second", this.getClass().getSimpleName()))
                .retryWhen(errors -> errors.delay(1, TimeUnit.SECONDS))
                .repeatWhen(completed -> completed.delay(1, TimeUnit.SECONDS))
                .doOnNext(event -> logger.debug("{} received event in thread {}", this.getClass().getSimpleName(), Thread.currentThread().getName()));
        
        observable = decorate(coldObservable)
                .doOnNext(event -> logger.debug("{} dispatching event to subscribers in thread {}", this.getClass().getSimpleName(), Thread.currentThread().getName()))
                .subscribeOn(Schedulers.io())
                .publish();
        
        // subscribe controllers
        subscribers.forEach(s -> observable.subscribe(s));
        
        // subscribe in-house error handler
        observable.subscribe(new ErrorHandler());
        
        // start emitting event
        observable.connect();
    }
    
    /**
     * Register an handler to the pipeline end
     *
     * @param handler a handler or any object that implements the Subscriber interface
     */
    public void subscribe(Observer<DataT> handler) {
        subscribers.add(handler);
    }
    
    /**
     * Allows implementations to add logic before the event is sent to the handler
     *
     * @param observable the observable
     */
    protected Observable<DataT> decorate(Observable<DataT> observable) {
        return observable;
    }
    
    
    private class ErrorHandler implements Observer<DataT> {
        
        @Override
        public void onError(Throwable e) {
            // should be unreachable
            logger.error("pipeline {} errored with {}", EventPipeline.this.getClass().getSimpleName(), e);
        }
        
        @Override
        public void onComplete() {
            // should be unreachable
            logger.error("pipeline {} completed", EventPipeline.this.getClass().getSimpleName());
        }
        
        @Override
        public void onSubscribe(Disposable d) {
        
        }
        
        @Override
        public void onNext(DataT data) {
        }
    }
}
