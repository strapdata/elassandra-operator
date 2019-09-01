package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Session;
import com.strapdata.model.k8s.cassandra.DataCenter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.Locale;

@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
public class CqlKeyspace {

    String name;
    int rf;

    /**
     * create keyspace if not exists.
     *
     * @param dataCenter
     * @param session
     * @return
     */
    CqlKeyspace createIfNotExistsKeyspace(final DataCenter dataCenter, final Session session) {
        if (rf > 0) {
            int targetRf = Math.max(1, Math.min(rf, dataCenter.getStatus().getReplicas()));
            session.execute(String.format(Locale.ROOT, "CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH replication = {'class': 'NetworkTopologyStrategy', '%s':'%d'}; ",
                    name, dataCenter.getSpec().getDatacenterName(), targetRf));
        }
        return this;
    }
}
