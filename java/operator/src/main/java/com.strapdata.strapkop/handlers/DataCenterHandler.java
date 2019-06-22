package com.strapdata.strapkop.handlers;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipelines.K8sWatchEvent;
import com.strapdata.strapkop.reconciliers.DataCenterReconcilier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static com.strapdata.strapkop.pipelines.K8sWatchEvent.Type.*;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);
    
    private static final EnumSet<K8sWatchEvent.Type> creationEventTypes = EnumSet.of(ADDED, MODIFIED, INITIAL);
    private static final EnumSet<K8sWatchEvent.Type> deletionEventTypes = EnumSet.of(DELETED);
    
    private final DataCenterReconcilier dataCenterReconcilier;
    
    public DataCenterHandler(DataCenterReconcilier dataCenterReconcilier) {
        this.dataCenterReconcilier = dataCenterReconcilier;
    }
    
    @Override
    public void accept(K8sWatchEvent<DataCenter> data) throws Exception {
        logger.info("processing a DataCenter event");
        
        if (creationEventTypes.contains(data.getType())) {
            dataCenterReconcilier.enqueueUpdate(data.getResource());
        }
   
        else if (deletionEventTypes.contains(data.getType())) {
            dataCenterReconcilier.enqueueDelete(data.getResource());
        }
    }
}
