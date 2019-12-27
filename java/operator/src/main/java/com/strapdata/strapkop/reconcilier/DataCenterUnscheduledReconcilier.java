package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import io.kubernetes.client.ApiException;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class DataCenterUnscheduledReconcilier extends Reconcilier<Tuple2<Key, ElassandraPod>> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterUnscheduledReconcilier.class);

    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;

    public DataCenterUnscheduledReconcilier(final ReconcilierObserver reconcilierObserver,
                                            final ApplicationContext context,
                                            final K8sResourceUtils k8sResourceUtils) {
        super(reconcilierObserver);
        this.context = context;
        this.k8sResourceUtils = k8sResourceUtils;
    }

    @Override
    public Completable reconcile(final Tuple2<Key, ElassandraPod> tuple) throws ApiException, InterruptedException {
        // this is a "read-before-write" to ensure we are processing the latest resource version (otherwise, status update will failed with a 409 conflict)
        return k8sResourceUtils.readDatacenter(tuple._1)
                .flatMap(dc -> reconcilierObserver.onReconciliationBegin().toSingleDefault(dc))
                .flatMapCompletable(dc -> {
                    if (dc.getStatus() != null && !Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.UPDATING)) {
                        logger.debug("do not reconcile datacenter on unscheduled pod, the DataCenter phase is  ({})", dc.getStatus().getPhase());
                        return Completable.complete();
                    }

                    try {
                        // call the statefullset reconciliation  (before scaling up/down to properly stream data according to the adjusted RF)
                        logger.trace("processing an UnscheduledPod during datacenter reconciliation request for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());

                        return context.createBean(DataCenterUpdateAction.class, dc)
                                .switchDataCenterUpdateOff(tuple._2)
                                .andThen(k8sResourceUtils.updateDataCenterStatus(dc).ignoreElement());
                    } catch (Exception e) {
                        logger.error("an error occurred while processing UnscheduledPod during DataCenter update reconciliation for {}", tuple._1.getName(), e);
                        if (dc != null) {
                            if (dc.getStatus() == null) {
                                dc.setStatus(new DataCenterStatus());
                            }
                            dc.getStatus().setPhase(DataCenterPhase.ERROR);
                            dc.getStatus().setLastMessage(e.getMessage());
                            k8sResourceUtils.updateDataCenterStatus(dc);
                        }
                        throw e;
                    }
                })
                .doOnError(t -> { if (!(t instanceof ReconcilierShutdownException)) reconcilierObserver.failedReconciliationAction(); })
                .doOnComplete(reconcilierObserver.endReconciliationAction())
                .observeOn(Schedulers.io());
    }
    
    

}
