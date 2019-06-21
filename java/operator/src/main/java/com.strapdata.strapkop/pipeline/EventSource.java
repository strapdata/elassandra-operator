package com.strapdata.strapkop.pipeline;


import io.reactivex.Observable;

public interface EventSource<KeyT, DataT> {
    
    // not sure we should switch to flowable
    Observable<Event<KeyT, DataT>> createObservable() throws Exception;
}
