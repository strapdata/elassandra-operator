package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterDeleteReconcilier;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;
    private final DataCenterDeleteReconcilier dataCenterDeleteReconcilier;

    public DataCenterHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterUpdateReconcilier, DataCenterDeleteReconcilier dataCenterDeleteReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterUpdateReconcilier = dataCenterUpdateReconcilier;
        this.dataCenterDeleteReconcilier = dataCenterDeleteReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        logger.debug("Processing a DataCenter event={}", event);
        
        Completable completable = null;
        if (event.isUpdate()) {
            completable = dataCenterUpdateReconcilier.reconcile(new Key(event.getResource().getMetadata()));
        }
        else if (event.isDeletion()) {
            completable = dataCenterDeleteReconcilier.reconcile(event.getResource());
        }
        else {
            return;
        }

        workQueue.submit(new ClusterKey(event.getResource()), completable);
    }
}
