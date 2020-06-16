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
