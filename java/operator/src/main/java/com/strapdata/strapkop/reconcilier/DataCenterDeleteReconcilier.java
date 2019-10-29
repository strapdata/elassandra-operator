package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.micronaut.context.ApplicationContext;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;

@Singleton
public class DataCenterDeleteReconcilier extends Reconcilier<DataCenter> {
    
    private final Logger logger = LoggerFactory.getLogger(DataCenterDeleteReconcilier.class);
    
    private final ApplicationContext context;
    private final CqlConnectionManager cqlConnectionManager;
    private final PluginRegistry pluginRegistry;

    public DataCenterDeleteReconcilier(final ApplicationContext context,
                                       final CqlConnectionManager cqlConnectionManager,
                                       final PluginRegistry pluginRegistry) {
        this.context = context;
        this.cqlConnectionManager = cqlConnectionManager;
        this.pluginRegistry = pluginRegistry;
    }
    
    @Override
    public Completable reconcile(final DataCenter dc) throws Exception {
        cqlConnectionManager.removeConnection(dc);
        return Completable.mergeArray(pluginRegistry.deleteAll(dc))
                .andThen(context.createBean(DataCenterDeleteAction.class, dc).deleteDataCenter());
    }
}
