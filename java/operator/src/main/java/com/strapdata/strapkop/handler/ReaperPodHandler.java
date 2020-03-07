package com.strapdata.strapkop.handler;

import com.strapdata.strapkop.cache.DataCenterCache;
import com.strapdata.strapkop.event.K8sWatchEvent;
import com.strapdata.strapkop.event.ReaperPod;
import com.strapdata.strapkop.model.ClusterKey;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.WorkQueues;
import com.strapdata.strapkop.plugins.ReaperPlugin;
import io.kubernetes.client.models.V1Pod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.strapdata.strapkop.event.K8sWatchEvent.Type.MODIFIED;

@Handler
public class ReaperPodHandler extends TerminalHandler<K8sWatchEvent<V1Pod>> {

    private final Logger logger = LoggerFactory.getLogger(ReaperPodHandler.class);

    private final WorkQueues workQueues;
    private final ReaperPlugin reaperPlugin;
    private final DataCenterCache dataCenterCache;

    public ReaperPodHandler(WorkQueues workQueue,
                            DataCenterCache dataCenterCache,
                            ReaperPlugin reaperPlugin) {
        this.workQueues = workQueue;
        this.dataCenterCache = dataCenterCache;
        this.reaperPlugin = reaperPlugin;
     }
    
    @Override
    public void accept(K8sWatchEvent<V1Pod> event) throws Exception {
        V1Pod v1Pod = event.getResource();
        ReaperPod pod = new ReaperPod(v1Pod);
        logger.debug("ReaperPod event type={} pod={}/{} ready={}",
                event.getType(), v1Pod.getMetadata().getName(), v1Pod.getMetadata().getNamespace(), pod.isReady());

        if (event.getType().equals(MODIFIED)) {
            // currently for reaper watch only MODIFIED status to try a cluster registration
            if (pod.isReady()) {
                ClusterKey clusterKey = new ClusterKey(pod.getClusterName(), pod.getNamespace());
                DataCenter dataCenter = dataCenterCache.get(new Key(pod.getElassandraDatacenter(), pod.getNamespace()));
                if (dataCenter != null) {
                    logger.debug("datacenter={} submit to register", dataCenter.id());
                    workQueues.submit(clusterKey, reaperPlugin.registerIfNeeded(dataCenter));
                }
            }
        }
    }

}
