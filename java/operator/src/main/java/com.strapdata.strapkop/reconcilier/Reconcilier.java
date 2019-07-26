package com.strapdata.strapkop.reconcilier;

import io.reactivex.Completable;

/**
 * Base class for the reconcilier objects
 * @param <T>
 */
public abstract class Reconcilier<T> {
    
    /**
     * Reconcilier must implement the reconciliation logic in this method
     */
    abstract void reconcile(T value) throws Exception;
    
    /**
     * Convert the reconciliation logic into a Rx Completable.
     * This can potentially be overridden to add specific scheduler, retry policy, timeout...
     */
    public Completable asCompletable(T value) {
        return Completable.fromCallable(() -> { this.reconcile(value); return null; });
    }
}
