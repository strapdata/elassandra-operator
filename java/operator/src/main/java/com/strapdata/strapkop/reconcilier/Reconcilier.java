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
    public abstract Completable reconcile(T value) throws Exception;

}
