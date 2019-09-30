package com.strapdata.strapkop.reconcilier;

import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.cql.CqlConnectionManager;
import com.strapdata.strapkop.plugins.Plugin;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.micronaut.context.ApplicationContext;
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
    void reconcile(final DataCenter dc) {
        
        try {
            logger.debug("processing a dc delete reconciliation for {} in thread {}", dc.getMetadata().getName(), Thread.currentThread().getName());

            // ordering may be important ?
            for(Plugin plugin : pluginRegistry.plugins()) {
                try {
                    plugin.delete(dc);
                } catch(Exception e) {
                    logger.error("Plugin class="+plugin.getClass().getSimpleName()+" reconcilation failed:", e);
                }
            }

            context.createBean(DataCenterDeleteAction.class, dc).deleteDataCenter();
            
            cqlConnectionManager.removeConnection(dc);
        }
        catch (Exception e) {
            logger.error("an error occurred while processing DataCenter update reconciliation for {}", dc.getMetadata().getName(), e);
        }
    }
}
