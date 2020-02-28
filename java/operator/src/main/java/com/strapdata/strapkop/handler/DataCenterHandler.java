package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterDeleteReconcilier;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    private final WorkQueues workQueues;
    private final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;
    private final DataCenterDeleteReconcilier dataCenterDeleteReconcilier;

    public DataCenterHandler(WorkQueues workQueue, DataCenterUpdateReconcilier dataCenterUpdateReconcilier, DataCenterDeleteReconcilier dataCenterDeleteReconcilier) {
        this.workQueues = workQueue;
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

        workQueues.submit(new ClusterKey(event.getResource()), completable);
    }
}
