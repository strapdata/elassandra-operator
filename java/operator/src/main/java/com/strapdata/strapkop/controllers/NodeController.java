package com.strapdata.strapkop.controllers;

import com.strapdata.model.ClusterKey;
import com.strapdata.model.Key;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.pipeline.WorkQueue;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.ApiException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;


@Controller("/node")
public class NodeController {

    private final Logger logger = LoggerFactory.getLogger(NodeController.class);

    @Inject
    ElassandraNodeStatusCache elassandraNodeStatusCache;

    @Inject
    WorkQueue workQueue;

    @Inject
    DataCenterUpdateReconcilier dataCenterUpdateReconcilier;

    /**
     * Update the Elassandra node status and trigger a reconciliation
     * @param namespace
     * @param endpoint IP address
     * @param status
     * @return
     * @throws ApiException
     * @throws UnknownHostException
     */
    @Post(value = "/{namespace}/{endpoint}/{status}", produces = MediaType.APPLICATION_JSON)
    public HttpStatus update(String namespace, String endpoint, ElassandraNodeStatus status) throws ApiException, UnknownHostException {
        InetAddress addr = InetAddress.getByName(endpoint);
        String fqdnPodName = addr.getHostName();
        String podName = fqdnPodName.substring(0, fqdnPodName.indexOf("."));
        logger.debug("Update cache for namespace={} endpoint={} podName={} status={}", namespace, endpoint, podName, status);
        ElassandraPod pod = ElassandraPod.fromName(namespace, podName);
        ElassandraNodeStatus prevStatus = elassandraNodeStatusCache.put(pod, status);
        if (prevStatus == null || !Objects.equals(status, prevStatus)) {
           ClusterKey clusterKey = new ClusterKey(pod.getCluster(), namespace);
           Key dcKey = new Key(OperatorNames.dataCenterResource(pod.getCluster(), pod.getDataCenter()), namespace);
           logger.debug("Summit a reconcilation for namespace={} cluster={} dc={}", pod.getCluster(), pod.getDataCenter());
           workQueue.submit(clusterKey, dataCenterUpdateReconcilier.reconcile(dcKey));
        }
        return HttpStatus.OK;
    }

    /**
     * Returns the current Elassandra node status from the cache
     * @param namespace
     * @param podName short DNS pod name
     * @return
     */
    @Get(value = "/{namespace}/{podName}", produces = MediaType.APPLICATION_JSON)
    public ElassandraNodeStatus update(String namespace, String podName)  {
        ElassandraPod pod = ElassandraPod.fromName(namespace, podName);
        return elassandraNodeStatusCache.getOrDefault(pod, ElassandraNodeStatus.UNKNOWN);
    }
}
