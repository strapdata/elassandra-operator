package com.strapdata.strapkop.controllers;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.ElassandraNodeStatusCache;
import com.strapdata.strapkop.event.ElassandraPod;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterUpdateReconcilier;
import io.kubernetes.client.ApiException;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.strapdata.strapkop.OperatorTags.*;


@Controller("/node")
public class NodeController {

    private final Logger logger = LoggerFactory.getLogger(NodeController.class);

    @Inject
    ElassandraNodeStatusCache elassandraNodeStatusCache;

    @Inject
    WorkQueues workQueue;

    @Inject
    DataCenterUpdateReconcilier dataCenterUpdateReconcilier;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Update the Elassandra node status and trigger a reconciliation
     *
     * @param namespace
     * @param endpoint  IP address
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
        Iterable<Tag> tags = ImmutableList.of(new ImmutableTag(CLUSTER.name(), pod.getCluster()),
                new ImmutableTag(DATACENTER.name(), pod.getDataCenter()),
                new ImmutableTag(NAMESPACE.name(), pod.getNamespace()));

        meterRegistry.counter("notification.count", tags).increment();
        if (prevStatus == null || !Objects.equals(status, prevStatus)) {
            ClusterKey clusterKey = new ClusterKey(pod.getCluster(), namespace);
            Key dcKey = new Key(OperatorNames.dataCenterResource(pod.getCluster(), pod.getDataCenter()), namespace);
            logger.debug("Summit a reconcilation for namespace={} cluster={} dc={}", pod.getCluster(), pod.getDataCenter());
            workQueue.submit(clusterKey, dataCenterUpdateReconcilier.reconcile(dcKey));
            meterRegistry.counter("notification.reconciliation", tags).increment();
        }
        return HttpStatus.OK;
    }

    /**
     * Returns the current Elassandra datacenter status from the cache
     * @param namespace
     * @param cluster
     * @param datacenter
     * @return
     */
    @Get(value = "/{namespace}", produces = MediaType.APPLICATION_JSON)
    public Map<String, ElassandraNodeStatus> getPods(String namespace,
                                                    @Nullable @QueryValue("cluster") String cluster,
                                                    @Nullable @QueryValue("datacenter") String datacenter) {
        return elassandraNodeStatusCache.entrySet().stream()
                .filter(e -> e.getKey().getNamespace().equals(namespace) &&
                        (cluster == null || e.getKey().getCluster().equals(cluster)) &&
                        (datacenter == null || e.getKey().getDataCenter().equals(datacenter)))
                .collect(Collectors.toMap(e -> e.getKey().getName(), e -> e.getValue()));
    }

    /**
     * Returns the current Elassandra node status from the cache
     *
     * @param namespace
     * @param podName   short DNS pod name
     * @return
     */
    @Get(value = "/{namespace}/{podName}", produces = MediaType.APPLICATION_JSON)
    public ElassandraNodeStatus getPod(String namespace, String podName) {
        ElassandraPod pod = ElassandraPod.fromName(namespace, podName);
        return elassandraNodeStatusCache.getOrDefault(pod, ElassandraNodeStatus.UNKNOWN);
    }
}
