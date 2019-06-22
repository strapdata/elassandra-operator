package com.strapdata.strapkop.handlers;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class TerminalHandler<DataT> implements Observer<DataT> {
    
    private final Logger logger = LoggerFactory.getLogger(TerminalHandler.class);
    
    @Override
    public void onComplete() {
    }
    
    @Override
    public void onError(Throwable e) {
    }
    
    @Override
    public void onSubscribe(Disposable d) {
    }
    
    @Override
    public void onNext(DataT data) {
        try {
            accept(data);
        } catch (Exception e) {
            logger.error("exception thrown in controller {}", this.getClass().getSimpleName());
            e.printStackTrace();
            // TODO: what to do if a controller throws an exception ?
        }
    }
    
    protected abstract void accept(DataT data) throws Exception;
}
