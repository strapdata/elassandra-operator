package com.strapdata.strapkop;

import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.instaclustr.model.Key;
import com.instaclustr.model.k8s.cassandra.DataCenter;
import com.instaclustr.model.sidecar.NodeStatus;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.models.V1Pod;
import io.micronaut.context.annotation.Context;
import io.reactivex.Single;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Pool nodes status every minutes
 */
@Context
public class CassandraHealthCheckService extends AbstractScheduledService {
    private static final Logger logger = LoggerFactory.getLogger(CassandraHealthCheckService.class);

    private final K8sResourceUtils k8sResourceUtils;
    private final Map<Key<DataCenter>, DataCenter> dataCenterCache = new HashMap<>();
    private final Map<InetAddress, NodeStatus> cassandraNodeStatus = new ConcurrentHashMap<>();
    private final BehaviorSubject<CassandraNodeStatusEvent> behaviorSubject = BehaviorSubject.create();
    private final SidecarClientFactory sidecarClientFactory;

    @Inject
    public CassandraHealthCheckService(final K8sResourceUtils k8sResourceUtils,
                                       final SidecarClientFactory sidecarClientFactory) {
        logger.debug("Initializing CassandraHealthCheckService");
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
    }

    public Subject<CassandraNodeStatusEvent> getSubject() {
        return this.behaviorSubject;
    }

    public Map<InetAddress, NodeStatus> getCassandraNodeStatus() {
        return cassandraNodeStatus;
    }

    @Override
    protected void runOneIteration() throws Exception {
        logger.debug("Checking health of Cassandra instances.");

        for (final Map.Entry<Key<DataCenter>, DataCenter> cacheEntry : dataCenterCache.entrySet()) {
            final Key<DataCenter> dataCenterKey = cacheEntry.getKey();
            final String labelSelector = String.format("%s=%s", OperatorLabels.DATACENTER, dataCenterKey.name);
            final Iterable<V1Pod> pods = k8sResourceUtils.listNamespacedPods(dataCenterKey.namespace, "status.phase=Running", labelSelector);

            Single.zip(
                StreamSupport.stream(pods.spliterator(), false).map(pod -> {
                    return sidecarClientFactory.clientForPodNullable(pod).status().map(status -> {
                        final InetAddress podIp = InetAddresses.forString(pod.getStatus().getPodIP());
                        final NodeStatus previousMode = cassandraNodeStatus.get(podIp);
                        final NodeStatus mode = status;
                        logger.debug("Cassandra node {} has OperationMode = {current: {}, previous: {}}.", podIp, mode, previousMode);
                        cassandraNodeStatus.put(podIp, mode);
                        if (previousMode == null || !previousMode.equals(mode)) {
                            behaviorSubject.onNext(new CassandraNodeStatusEvent(pod, dataCenterKey, previousMode, mode));
                        }
                        return mode;
                    });
                }).collect(Collectors.toList()),
                modes -> {
                    logger.debug("Got pod status for datacenter={}", dataCenterKey.name);
                    return null;
                });
        }
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(0, 1, TimeUnit.MINUTES);
    }
}
