package com.strapdata.strapkop.sidecar;

import com.strapdata.strapkop.cache.SidecarConnectionCache;
import com.strapdata.strapkop.event.ElassandraPod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * This is a sidecar client factory that caches client and reuse it as possible.
 *
 * The periodic node status checker should invalidate cache entry that are not working.
 * Java DNS caching has been disabled. If a pod is restarted and its IP change,
 * the next nodeStatus check would invalidate the cache (calling invalidateClient()), and the next call to the factory would recreate the client.
 */
@Singleton
public class SidecarClientFactory {
    
    static final Logger logger = LoggerFactory.getLogger(SidecarClientFactory.class);
    
    private final SidecarConnectionCache sidecarConnectionCache;
    
    public SidecarClientFactory(SidecarConnectionCache sidecarConnectionCache) {
        this.sidecarConnectionCache = sidecarConnectionCache;
    }
    
    /**
     * Get a sidecar client from cache or create it
     */
    public SidecarClient clientForPod(final ElassandraPod pod) throws MalformedURLException {
        
        SidecarClient sidecarClient = sidecarConnectionCache.get(pod);
        
        if (sidecarClient != null && sidecarClient.isRunning()) {
            logger.debug("hitting sidecar client cache for pod={}", pod.getName());
            return sidecarClient;
        }

        logger.debug("creating sidecar for pod={}", pod.getName());
        sidecarClient = new SidecarClient(new URL("http://" + pod.getFqdn() + ":8080"));
        sidecarConnectionCache.put(pod, sidecarClient);
        return sidecarClient;
    }
    
    
    /**
     * Remove and close a sidecar client from cache
     */
    public void invalidateClient(ElassandraPod pod) {
        logger.debug("invalidating cached sidecar client for pod={}", pod.getName());
    
        final SidecarClient sidecarClient = sidecarConnectionCache.remove(pod);
    
        if (sidecarClient != null) {
            sidecarClient.close();
        }
    }
}
