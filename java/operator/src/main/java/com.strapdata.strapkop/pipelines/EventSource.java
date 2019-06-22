package com.strapdata.strapkop.pipelines;


import io.reactivex.Observable;

public interface EventSource<DataT> {
    
    // not sure we should switch to flowable
    Observable<DataT> createObservable() throws Exception;
}
