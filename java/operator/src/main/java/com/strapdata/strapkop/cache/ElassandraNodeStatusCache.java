package com.strapdata.strapkop.cache;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.event.ElassandraPod;

import javax.inject.Singleton;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

@Singleton
public class ElassandraNodeStatusCache extends Cache<ElassandraPod, ElassandraNodeStatus> {
    
    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e ->
                Objects.equals(e.getKey().getParent(), dc.getMetadata().getName()) &&
                        Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace()));
    }

    public boolean isNormal(ElassandraPod pod) {
        return ElassandraNodeStatus.NORMAL.equals(getOrDefault(pod, ElassandraNodeStatus.UNKNOWN));
    }

    public long countNodesInStateForRack(String rack, ElassandraNodeStatus status) {
        Objects.requireNonNull(rack, "rack parameter is null");
        Objects.requireNonNull(status, "status parameter is null");
        long result =  this.entrySet().stream()
                .filter(e -> rack.equals(e.getKey().getRack()))
                .filter(e -> status.equals(e.getValue()))
                .count();
        return result;
    }

    public Optional<ElassandraPod> getLastBootstrappedNodesForRack(String rack) {
        Objects.requireNonNull(rack, "rack parameter is null");
        Optional<ElassandraPod> pod = this.entrySet().stream()
                .filter(e -> rack.equals(e.getKey().getRack()))
                .filter(e -> ElassandraNodeStatus.NORMAL.equals(e.getValue()))
                .sorted(new Comparator<Entry<ElassandraPod, ElassandraNodeStatus>>() {
                    @Override
                    public int compare(Entry<ElassandraPod, ElassandraNodeStatus> elassandraPodElassandraNodeStatusEntry, Entry<ElassandraPod, ElassandraNodeStatus> t1) {
                        return podIndex(elassandraPodElassandraNodeStatusEntry.getKey().getName()) - podIndex(t1.getKey().getName());
                    }
                }).findFirst().map(Entry::getKey);
        return pod;
    }

    private int podIndex(String podName) {
        int index = podName.lastIndexOf("-");
        return Integer.parseInt(podName.substring(index));
    }
}
