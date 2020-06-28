/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.reconcilier;

import io.reactivex.Completable;

import javax.inject.Inject;

/**
 * Base class for the reconcilier objects
 * @param <T>
 */
public abstract class Reconcilier<T> {

    @Inject
    protected ReconcilierObserver reconcilierObserver;

    public Reconcilier() {}

    /**
     * Reconcilier must implement the reconciliation logic in this method
     */
    public Completable reconcile(T value) {
        return Completable.complete();
    }

    /**
     * Reconcilier must implement the reconciliation logic in this method
     */
    public Completable reconcile(T value, Completable action) {
        return Completable.complete();
    }

}
