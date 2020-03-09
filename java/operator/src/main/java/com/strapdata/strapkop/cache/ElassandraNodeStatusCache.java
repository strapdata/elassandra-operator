package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.model.sidecar.ElassandraNodeStatus;
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

    public long countNodesInStateForRack(Integer rackIndex, ElassandraNodeStatus status) {
        Objects.requireNonNull(rackIndex, "rackIndex parameter is null");
        Objects.requireNonNull(status, "status parameter is null");
        long result =  this.entrySet().stream()
                .filter(e -> rackIndex.equals(e.getKey().getRackIndex()))
                .filter(e -> status.equals(e.getValue()))
                .count();
        return result;
    }

    public Optional<ElassandraPod> getLastBootstrappedNodesForRack(Integer rackIndex) {
        Objects.requireNonNull(rackIndex, "rackIndex parameter is null");
        Optional<ElassandraPod> pod = this.entrySet().stream()
                .filter(e -> rackIndex.equals(e.getKey().getRackIndex()))
                .filter(e -> ElassandraNodeStatus.NORMAL.equals(e.getValue()))
                .sorted(new Comparator<Entry<ElassandraPod, ElassandraNodeStatus>>() {
                    @Override
                    public int compare(Entry<ElassandraPod, ElassandraNodeStatus> elassandraPodElassandraNodeStatusEntry, Entry<ElassandraPod, ElassandraNodeStatus> t1) {
                        return ElassandraPod.podIndex(elassandraPodElassandraNodeStatusEntry.getKey().getName()) - ElassandraPod.podIndex(t1.getKey().getName());
                    }
                }).findFirst().map(Entry::getKey);
        return pod;
    }

    public Optional<ElassandraPod> findPodByName(String name) {
        Objects.requireNonNull(name, "name parameter is null");
        Optional<ElassandraPod> pod = this.entrySet().stream()
                .filter(e -> name.equals(e.getKey().getName()))
                .findFirst().map(Entry::getKey);
        return pod;
    }

    public Optional<ElassandraPod> findFirstPodByStatus(ElassandraNodeStatus status) {
        return this.entrySet().stream()
                .filter(e -> status.equals(e.getValue()))
                .findFirst().map(Entry::getKey);
    }
}
