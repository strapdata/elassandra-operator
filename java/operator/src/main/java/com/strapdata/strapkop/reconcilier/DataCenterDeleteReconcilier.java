package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
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
    private final PluginRegistry pluginRegistry;
    private final CqlRoleManager cqlRoleManager;

    public DataCenterDeleteReconcilier(final ApplicationContext context,
                                       final CqlRoleManager cqlRoleManager,
                                       final PluginRegistry pluginRegistry) {
        this.context = context;
        this.pluginRegistry = pluginRegistry;
        this.cqlRoleManager = cqlRoleManager;
    }
    
    @Override
    public Completable reconcile(final DataCenter dataCenter) throws Exception {
        CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
        return Completable.mergeArray(pluginRegistry.deleteAll(dataCenter))
                .andThen(context.createBean(DataCenterDeleteAction.class, dataCenter).deleteDataCenter(cqlSessionHandler))
                .andThen(cqlSessionHandler.close());

    }
}
