package com.strapdata.strapkop;

import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.handler.Handler;
import com.strapdata.strapkop.handler.TerminalHandler;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

@Handler
public class DataCenterHandler extends TerminalHandler<K8sWatchEvent<DataCenter>> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterHandler.class);

    DataCenterPhase phase;
    CountDownLatch countDownLatch;

    public DataCenterHandler(CountDownLatch countDownLatch, DataCenterPhase phase) {
        this.phase = phase;
        this.countDownLatch = countDownLatch;
    }

    @Override
    public void onError(Throwable e) {
        e.printStackTrace();
        System.exit(1);
    }

    @Override
    public void accept(K8sWatchEvent<DataCenter> event) throws Exception {
        final DataCenter dataCenter = event.getResource();
        final Key key = new Key(event.getResource().getMetadata());
        logger.debug("DataCenter event type={} dc={}", event.getType(), dataCenter.id());

        Completable completable = null;
        switch(event.getType()) {
            case ADDED:
            case INITIAL:
                break;
            case MODIFIED:
                if (dataCenter.getStatus().getPhase().equals(phase)) {
                    countDownLatch.countDown();
                }
                break;
            case DELETED:
                break;
            case ERROR:
                throw new IllegalStateException("Datacenter error event");
            default:
                throw new UnsupportedOperationException("Unknown event type");
        }
    }
}
