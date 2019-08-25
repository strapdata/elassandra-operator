package com.strapdata.strapkop.cache;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.event.ElassandraPod;

import javax.inject.Singleton;
import java.util.Objects;

@Singleton
public class ElassandraNodeStatusCache extends Cache<ElassandraPod, ElassandraNodeStatus> {
    
    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e ->
                Objects.equals(e.getKey().getParent(), dc.getMetadata().getName()) &&
                        Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace()));
    }
}
