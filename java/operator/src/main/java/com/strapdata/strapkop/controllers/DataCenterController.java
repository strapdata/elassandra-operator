package com.strapdata.strapkop.controllers;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterRollbackReconcilier;
import io.kubernetes.client.ApiException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

@Controller("/datacenter")
public class DataCenterController {

    private final Logger logger = LoggerFactory.getLogger(DataCenterController.class);

    @Inject
    WorkQueue workQueue;

    @Inject
    DataCenterRollbackReconcilier dataCenterRollbackReconcilier;

    @Post(value = "/{namespace}/{cluster}/{datacenter}/rollback", produces = MediaType.APPLICATION_JSON)
    public HttpStatus rollback(String namespace, String cluster, String datacenter) throws ApiException {
        ClusterKey clusterKey = new ClusterKey(cluster, namespace);
        Key dcKey = new Key(OperatorNames.dataCenterResource(cluster, datacenter), namespace);
        logger.debug("Summit a configuration rollback for namespace={} cluster={} dc={}", namespace, cluster, datacenter);
        workQueue.submit(clusterKey, dataCenterRollbackReconcilier.reconcile(dcKey));
        return HttpStatus.ACCEPTED;
    }
}
