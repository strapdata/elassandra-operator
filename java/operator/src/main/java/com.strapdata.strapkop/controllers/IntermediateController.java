package com.strapdata.strapkop.controllers;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

import java.util.Optional;

public abstract class IntermediateController<DataInT, DataOutT> implements Observer<DataInT> {
    
    private Subject<DataInT> subject;
    private Observable<DataOutT> observable;
    
    public IntermediateController() {
        this.subject = BehaviorSubject.<DataInT>create().toSerialized();
        this.observable = subject.map(this::accept).filter(Optional::isPresent).map(Optional::get);
    }
    
    public Observable<DataOutT> asObservable() {
        return observable;
    }
    
    protected abstract Optional<DataOutT> accept(DataInT data) throws Exception;

    @Override
    public void onSubscribe(Disposable d) {
        subject.onSubscribe(d);
    }
    
    @Override
    public void onNext(DataInT dataIn) {
        subject.onNext(dataIn);
    }
    
    @Override
    public void onError(Throwable e) {
        subject.onError(e);
    }
    
    @Override
    public void onComplete() {
        subject.onComplete();
    }
}
