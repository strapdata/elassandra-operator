package com.strapdata.strapkop.source;

import com.strapdata.model.Key;
import com.strapdata.strapkop.OperatorConfig;
import com.strapdata.strapkop.cache.NodeStatusCache;
import com.strapdata.strapkop.event.NodeStatusEvent;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorLabels;
import com.strapdata.strapkop.sidecar.SidecarClientFactory;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Pod;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Singleton
public class NodeStatusSource implements EventSource<NodeStatusEvent> {
    
    private final Logger logger = LoggerFactory.getLogger(NodeStatusSource.class);
    
    
    private final NodeStatusCache nodeStatusCache;
    private final K8sResourceUtils k8sResourceUtils;
    private final SidecarClientFactory sidecarClientFactory;
    private final OperatorConfig config;
    
    public NodeStatusSource(NodeStatusCache nodeStatusCache, K8sResourceUtils k8sResourceUtils, SidecarClientFactory sidecarClientFactory, OperatorConfig config) {
        this.nodeStatusCache = nodeStatusCache;
        this.k8sResourceUtils = k8sResourceUtils;
        this.sidecarClientFactory = sidecarClientFactory;
        this.config = config;
    }
    
    @Override
    public Observable<NodeStatusEvent> createObservable() throws Exception {
        return Observable.interval(10, TimeUnit.SECONDS)
                .doOnNext(i -> logger.debug("run node status health check on thread {}", Thread.currentThread().getName()))
                .flatMap(i -> Observable.fromIterable(listPods()))
                .map(pod -> new NodeStatusEvent()
                        .setPod(pod)
                        .setDataCenterKey(new Key(pod.getMetadata().getLabels().get(OperatorLabels.PARENT), config.getNamespace()))
                )
                .flatMapSingle(event -> sidecarClientFactory.clientForPod(event.getPod()).status()
                        .doOnSubscribe(d -> logger.debug("requesting pod {} sidecar for health check on thread {}", event.getPod().getMetadata().getName(), Thread.currentThread().getName()))
                        .map(event::setCurrentMode)
                        .doOnError(throwable -> logger.warn("failed to get the status from sidecar pod {}", event.getPod().getMetadata().getName(), throwable))
                        .onErrorReturn(throwable -> event.setCurrentMode(null))
                        .subscribeOn(Schedulers.io()))
                .map(event -> event.setPreviousMode(nodeStatusCache.get(new Key(event.getPod().getMetadata()))))
                .doOnNext(event -> nodeStatusCache.insert(new Key(event.getPod().getMetadata()), event.getCurrentMode()))
                .filter(event -> !Objects.equals(event.getCurrentMode(), event.getPreviousMode()));
    }
    
    private Iterable<V1Pod> listPods() throws ApiException {
        return k8sResourceUtils.listNamespacedPods(config.getNamespace(), null,
                OperatorLabels.toSelector(OperatorLabels.MANAGED));
    }
}
