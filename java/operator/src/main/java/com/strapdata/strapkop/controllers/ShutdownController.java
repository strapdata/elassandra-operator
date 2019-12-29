package com.strapdata.strapkop.controllers;

import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.reconcilier.DataCenterDeleteReconcilier;
import com.strapdata.strapkop.reconcilier.ReconcilierObserver;
import io.kubernetes.client.ApiException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.reactivex.Observable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;


@Controller("/shutdown")
public class ShutdownController {

    private final Logger logger = LoggerFactory.getLogger(ShutdownController.class);

    @Inject
    ReconcilierObserver reconcilierObserver;

    @Inject
    K8sResourceUtils k8sResourceUtils;

    @Inject
    OperatorConfig operatorConfig;

    @Inject
    DataCenterDeleteReconcilier dataCenterDeleteReconcilier;

    public ShutdownController() {
    }

    /**
     * Delete all datacenters before returning.
     * @return List of deleted datacenters
     */
    @Post("/purge")
    public Single<List<String>> purge() throws ApiException {
            return Observable.fromIterable(k8sResourceUtils.listNamespacedDataCenters(operatorConfig.getNamespace(), null))
                    .flatMapSingle(dc -> {
                        logger.warn("Deleting datacenter={} in namespace={}", dc.getMetadata().getName(), dc.getMetadata().getNamespace());
                        return k8sResourceUtils.deleteDataCenter(dc.getMetadata()).map(dc2 -> dc2.getMetadata().getName());
                    })
                    .toList();
    }

    /**
     * Returns when all reconciliations are done and ignore new one.
     */
    @Post("/gracefull")
    public HttpStatus gracefulStop() {
        try {
            logger.warn("Gracefully stopping");
            reconcilierObserver.gracefullStop();
            return HttpStatus.OK;
        } catch(InterruptedException e) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }
}
