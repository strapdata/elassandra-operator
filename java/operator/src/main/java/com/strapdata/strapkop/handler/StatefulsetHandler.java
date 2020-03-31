package com.strapdata.strapkop.handler;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.OperatorLabels;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.k8s.cassandra.Operation;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.reconcilier.DataCenterController;
import io.kubernetes.client.models.V1StatefulSet;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Trigger a DC reconciliation when STS event occurs
 */
@Handler
public class StatefulsetHandler extends TerminalHandler<K8sWatchEvent<V1StatefulSet>> {

    private final Logger logger = LoggerFactory.getLogger(StatefulsetHandler.class);

    @Inject
    WorkQueues workQueues;

    @Inject
    DataCenterController dataCenterController;

    @Inject
    DataCenterCache dataCenterCache;

    @Inject
    MeterRegistry meterRegistry;

    Long managed = 0L;
    List<Tag> tags = ImmutableList.of(new ImmutableTag("type", "statefulset"));

    @PostConstruct
    public void initGauge() {
        meterRegistry.gauge("k8s.managed",  tags, managed);
    }

    /**
     * Update STS status cache and call dc controller if ready.
     * @param event
     * @throws Exception
     */
    @Override
    public void accept(K8sWatchEvent<V1StatefulSet> event) throws Exception {
        logger.debug("event={}", event);
        switch(event.getType()) {
            case INITIAL:
            case ADDED:
                meterRegistry.counter("k8s.event.added", tags).increment();
                managed++;
                break;
            case MODIFIED:
                meterRegistry.counter("k8s.event.modified", tags).increment();
                final V1StatefulSet sts = event.getResource();
                if (isStafulSetReady(sts)) {
                    final String clusterName = sts.getMetadata().getLabels().get(OperatorLabels.CLUSTER);
                    DataCenter dataCenter = dataCenterCache.get(new Key(sts.getMetadata().getLabels().get(OperatorLabels.PARENT), sts.getMetadata().getNamespace()));
                    if (dataCenter != null) {
                        logger.info("datacenter={} sts={}/{} is ready, triggering a dc statefulSetUpdate",
                                dataCenter.id(), sts.getMetadata().getName(), sts.getMetadata().getNamespace());
                        Operation op = new Operation().withSubmitDate(new Date()).withDesc("updated statefulset="+sts.getMetadata().getName());
                        workQueues.submit(
                                new ClusterKey(clusterName, sts.getMetadata().getNamespace()),
                                dataCenterController.statefulsetUpdate(op, dataCenter, sts)
                                        .onErrorComplete(t -> {
                                            logger.warn("datcenter={} statefulSetUpdate failed: {}", dataCenter.id(), t.toString());
                                            return t instanceof NoSuchElementException;
                                        }));
                    }
                }
                break;
            case DELETED:
                meterRegistry.counter("k8s.event.deleted", tags).increment();
                managed--;
                break;
            case ERROR:
                meterRegistry.counter("k8s.event.error", tags).increment();
                break;
        }
    }

    public static boolean isStafulSetReady(V1StatefulSet sts) {
        return Objects.equals(sts.getSpec().getReplicas(), ObjectUtils.defaultIfNull(sts.getStatus().getReadyReplicas(), 0)) &&
                Objects.equals(sts.getStatus().getCurrentRevision(), sts.getStatus().getUpdateRevision());
    }
}