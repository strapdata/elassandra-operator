package com.strapdata.strapkop.cql;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

@Data
@With
@NoArgsConstructor
@AllArgsConstructor
public class CqlKeyspace implements CqlReconciliable {

    private static final Logger logger = LoggerFactory.getLogger(CqlKeyspaceManager.class);

    // spec
    String name;
    int rf;
    boolean repair;

    // status
    boolean reconcilied;
    int reconcileWithDcSize;     // size of the DC when reconcilied

    @Override
    public boolean reconcilied() {
        return reconcilied;
    }

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
                sessionSupplier.getSessionWithSchemaAgreed(dataCenter)
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
