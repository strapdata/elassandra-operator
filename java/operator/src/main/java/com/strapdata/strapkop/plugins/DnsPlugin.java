package com.strapdata.strapkop.plugins;

import com.strapdata.dns.DnsUpdater;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import io.kubernetes.client.ApiException;
import io.reactivex.Completable;
import io.reactivex.Observable;

public abstract class DnsPlugin implements Plugin {

    DnsUpdater dnsUpdater;

    public DnsPlugin(DnsUpdater dnsUpdater) {
        this.dnsUpdater = dnsUpdater;
    }


    /**
     * Check the plugin is active in the specified datacenter.
     *
     * @param dataCenter
     * @return
     */
    @Override
    public boolean isActive(DataCenter dataCenter) {
        return true;
    }

    /**
     * Call on each reconciliation
     *
     * @param dataCenter
     */
    @Override
    public Completable reconcile(DataCenter dataCenter) throws ApiException, StrapkopException {
        return Completable.complete();
    }

    /**
     * Call when dc is reconcilied
     *
     * @param dataCenter
     */
    @Override
    public Completable reconciled(DataCenter dataCenter) throws ApiException, StrapkopException {
        return Completable.complete();
    }

    /**
     * Call when deleting the elassandra datacenter to remove seed DNS records of all racks.
     *
     * @param dataCenter
     */
    @Override
    public Completable delete(DataCenter dataCenter) throws ApiException {
        return Observable.fromIterable(dataCenter.getStatus().getRackStatuses().values()).flatMapCompletable(rackStatus ->
                Completable.create(emitter -> {
                    dnsUpdater.onStop(rackStatus.getName());
                    emitter.onComplete();
                }
        ));
    }
}
