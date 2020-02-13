package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterStatus;
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

@Singleton
public class DataCenterPodDeletedReconcilier extends Reconcilier<Tuple2<Key, ElassandraPod>> {

    private final Logger logger = LoggerFactory.getLogger(DataCenterPodDeletedReconcilier.class);

    private final ApplicationContext context;
    private final K8sResourceUtils k8sResourceUtils;

    public DataCenterPodDeletedReconcilier(final ReconcilierObserver reconcilierObserver,
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
                    // Test on the DataCenterPhase.SCALING_DOWN is safer but the pod deletion maybe triggered after the DC phase becomes RUNNING
                    // so we only test the pod status using the ElassandraNode cache inside the freePodResource method...
                    /*if (dc.getStatus() != null && !Objects.equals(dc.getStatus().getPhase(), DataCenterPhase.SCALING_DOWN)) {
                        logger.debug("do not free pod resources if the DC isn't scaling down, the DataCenter phase is  ({})", dc.getStatus().getPhase());
                        return Completable.complete();
                    }*/
                    try {
                        logger.trace("freeing resource of pod {} during datacenter reconciliation request for {} in thread {}", tuple._2.getName(), dc.getMetadata().getName(), Thread.currentThread().getName());
                        return context.createBean(DataCenterUpdateAction.class, dc).freePodResource(tuple._2);
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
