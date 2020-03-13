package com.strapdata.strapkop.cql;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Single;
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
     * @param sessionSupplier
     * @return
     */
    public Single<CqlKeyspace> createIfNotExistsKeyspace(final DataCenter dataCenter, final CqlSessionSupplier sessionSupplier) throws Exception {
        return (rf <= 0) ?
                Single.just(this) :
                sessionSupplier.getSession(dataCenter)
                    .flatMap(session -> {
                        int targetRf = Math.max(1, Math.min(rf, dataCenter.getSpec().getReplicas()));
                        return Single.fromFuture(session.executeAsync(
                                String.format(Locale.ROOT, "CREATE KEYSPACE IF NOT EXISTS \"%s\" WITH replication = {'class': 'NetworkTopologyStrategy', '%s':'%d'}; ",
                                name, dataCenter.getSpec().getDatacenterName(), targetRf)));
                    })
                    .map(x -> this);
    }
}
