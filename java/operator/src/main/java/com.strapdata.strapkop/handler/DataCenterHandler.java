package com.strapdata.strapkop.handler;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.reconcilier.DataCenterDeleteReconcilier;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import com.strapdata.strapkop.workqueue.WorkQueue;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.*;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);
    
    private final WorkQueue workQueue;
    private final DataCenterUpdateReconcilier dataCenterUpdateReconcilier;
    private final DataCenterDeleteReconcilier dataCenterDeleteReconcilier;
    
    public DataCenterHandler(WorkQueue workQueue, DataCenterUpdateReconcilier dataCenterUpdateReconcilier, DataCenterDeleteReconcilier dataCenterDeleteReconcilier) {
        this.workQueue = workQueue;
        this.dataCenterUpdateReconcilier = dataCenterUpdateReconcilier;
        this.dataCenterDeleteReconcilier = dataCenterDeleteReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> data) throws Exception {
        logger.info("processing a DataCenter event");
        
        Completable completable = null;
        if (creationEventTypes.contains(data.getType())) {
            completable = dataCenterUpdateReconcilier.asCompletable(data.getResource());
        }
   
        else if (deletionEventTypes.contains(data.getType())) {
            completable = dataCenterDeleteReconcilier.asCompletable(data.getResource());
        }
        else {
            return ; // unreachable
        }
        
        workQueue.submit(new ClusterKey(data.getResource()), completable);
    }
}
