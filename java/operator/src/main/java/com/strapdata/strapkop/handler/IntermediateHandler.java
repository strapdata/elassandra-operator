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

package com.strapdata.strapkop.handler;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;

import java.util.Optional;

public abstract class IntermediateHandler<DataInT, DataOutT> implements Observer<DataInT> {
    
    private Subject<DataInT> subject;
    private Observable<DataOutT> observable;
    
    public IntermediateHandler() {
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
