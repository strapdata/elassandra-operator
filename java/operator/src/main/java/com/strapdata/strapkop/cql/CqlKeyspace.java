package com.strapdata.strapkop.cql;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
public class CqlKeyspace {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    String name;
    int rf;

    /**
     * create keyspace if not exists.
     *
     * @param dataCenter
     * @param sessionSupplier
     * @return
     */
    public Single<CqlKeyspace> createIfNotExistsKeyspace(final DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) throws Exception {
        return (rf <= 0) ?
                Single.just(this) :
                sessionSupplier.getSession(dataCenter)
                    .flatMap(session -> {
                        int targetRf = Math.max(1, Math.min(rf, dataCenter.getSpec().getReplicas()));
                        String query = String.format(Locale.ROOT, "CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH replication = {'class': 'NetworkTopologyStrategy', '%s':'%d'}; ",
                                name, dataCenter.getSpec().getDatacenterName(), targetRf);
                        logger.debug("dc={} query={}", dataCenter.id(), query);
                        return Single.fromFuture(session.executeAsync(query));
                    })
                    .map(x -> this);
    }
}
