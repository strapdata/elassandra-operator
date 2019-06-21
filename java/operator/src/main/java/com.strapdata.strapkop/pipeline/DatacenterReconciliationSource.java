package com.strapdata.strapkop.pipeline;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.controllers.DataCenterIntermediateController;
import com.strapdata.strapkop.controllers.StatefulsetIntermediateController;
import io.reactivex.Observable;

import javax.inject.Singleton;

@Singleton
public class DatacenterReconciliationSource implements EventSource<Key, DataCenter> {
    
    private final StatefulsetIntermediateController statefulsetController;
    private final DataCenterIntermediateController dataCenterController;
    
    public DatacenterReconciliationSource(StatefulsetIntermediateController statefulsetController, DataCenterIntermediateController dataCenterController) {
        this.statefulsetController = statefulsetController;
        this.dataCenterController = dataCenterController;
    }
    
    @Override
    public Observable<Event<Key, DataCenter>> createObservable() throws Exception {
        return statefulsetController.asObservable()
                .mergeWith(dataCenterController.asObservable())
                .map(dc -> new Event<>(new Key(dc.getMetadata()), dc));
    }
}
