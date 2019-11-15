package com.strapdata.strapkop.cache;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterSpec;
import com.strapdata.model.sidecar.ElassandraNodeStatus;
import com.strapdata.strapkop.event.ElassandraPod;
import io.kubernetes.client.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestElassandraNodeStatusCache extends ElassandraNodeStatusCache {
    final String clusterName = "cl1";
    final String datacenterName = "dc1";
    final String podTemplate = "elassandra-"+clusterName+"-"+datacenterName+"-{}-{}";
    final String rack1 = "rack1";
    final int rack1Normal = 2;
    final String rack2 = "rack2";
    final int rack2Normal = 1;
    final String rack3 = "rack3";
    final int rack3Normal = 0;

    @BeforeEach
    public void init() {
        V1ObjectMeta v1ObjectMeta = new V1ObjectMeta();
        v1ObjectMeta.setNamespace("default");
        v1ObjectMeta.setName("elassandra-"+clusterName+"-"+datacenterName);
        DataCenter dc = new DataCenter()
                .setSpec(new DataCenterSpec()
                        .setClusterName(clusterName)
                        .setDatacenterName(datacenterName))
                .setMetadata(v1ObjectMeta);
        generateRackPods(dc, rack1, rack1Normal);
        generateRackPods(dc, rack2, rack2Normal);
        generateRackPods(dc, rack3, rack3Normal);

    }

    private void generateRackPods(DataCenter dc, String rack, int pod) {
        int i = 0;
        for (; i < pod; ++i) {
            this.putIfAbsent(ElassandraPod.fromName(dc, podTemplate.replaceFirst("\\{\\}", rack).replaceFirst("\\{\\}", "" + i)), ElassandraNodeStatus.NORMAL);
        }
        for (; i < pod*2; ++i) {
            this.putIfAbsent(ElassandraPod.fromName(dc, podTemplate.replaceFirst("\\{\\}", rack).replaceFirst("\\{\\}", "" + i)), i % 2 == 0 ? ElassandraNodeStatus.UNKNOWN : ElassandraNodeStatus.JOINING);
        }
    }

    @Test
    public void testInvalidRack() {
        assertEquals(0, countNodesInStateForRack("unknown", ElassandraNodeStatus.UNKNOWN));
    }

    @Test
    public void testCount() {
        assertEquals(rack1Normal, countNodesInStateForRack(rack1, ElassandraNodeStatus.NORMAL));
        assertEquals(rack2Normal, countNodesInStateForRack(rack2, ElassandraNodeStatus.NORMAL));
        assertEquals(rack3Normal, countNodesInStateForRack(rack3, ElassandraNodeStatus.NORMAL));
    }
}
