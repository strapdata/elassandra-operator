package com.strapdata.strapkop.reconcilier;

public abstract class Reconcilier<T> {
    
    abstract void process(T value);
    
    public Runnable prepareRunnable(T value) {
        return () -> process(value);
    }
}
