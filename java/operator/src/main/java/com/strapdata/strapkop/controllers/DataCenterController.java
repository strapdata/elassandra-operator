package com.strapdata.strapkop.controllers;

import io.micronaut.http.annotation.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/datacenter")
public class DataCenterController {

    private final Logger logger = LoggerFactory.getLogger(DataCenterController.class);

    /*
    @Inject
    WorkQueues workQueue;

    @Inject
    CheckPointCache checkPointCache;

    @Inject
    DataCenterController dataCenterController;

    @Post(value = "/{namespace}/{cluster}/{datacenter}/rollback", produces = MediaType.APPLICATION_JSON)
    public HttpStatus rollback(String namespace, String cluster, String datacenter) throws ApiException {
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        if (checkPointCache.containsKey(dcKey)) {
            ClusterKey clusterKey = new ClusterKey(cluster, namespace);
            logger.info("Summit a configuration rollback for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            workQueue.submit(clusterKey, dataCenterRollbackReconcilier.reconcile(dcKey));
            return HttpStatus.ACCEPTED;
        } else {
            logger.info("No restore point for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
            return HttpStatus.NO_CONTENT;
        }
    }

    @Post(value = "/{namespace}/{cluster}/{datacenter}/reconcile", produces = MediaType.APPLICATION_JSON)
    public HttpStatus reconcile(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        logger.info("Force a configuration reconciliation for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
        checkPointCache.remove(dcKey); // clear the restorePoint to take the current value of DC CRD
        workQueue.submit(clusterKey, dataCenterController.(dcKey));
        return HttpStatus.ACCEPTED;
    }
    */
}