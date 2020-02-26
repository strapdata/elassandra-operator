package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.cache.*;
import com.strapdata.strapkop.cql.CqlRoleManager;
import com.strapdata.strapkop.cql.CqlSessionHandler;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.plugins.PluginRegistry;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final CheckPointCache checkPointCache;
    private final DataCenterCache dataCenterCache;
    private final ElassandraNodeStatusCache elassandraNodeStatusCache;
    private final PodCache podCache;
    private final DeploymentCache deploymentCache;
    private final SidecarConnectionCache sidecarConnectionCache;
    private final StatefulsetCache statefulsetCache;
    private final TaskCache taskCache;
    private final MeterRegistry meterRegistry;

    public DataCenterDeleteReconcilier(final ReconcilierObserver reconcilierObserver,
                                       final ApplicationContext context,
                                       final CqlRoleManager cqlRoleManager,
                                       final PluginRegistry pluginRegistry,
                                       final CheckPointCache checkPointCache,
                                       final DataCenterCache dataCenterCache,
                                       final ElassandraNodeStatusCache elassandraNodeStatusCache,
                                       final PodCache podCache,
                                       final DeploymentCache deploymentCache,
                                       final SidecarConnectionCache sidecarConnectionCache,
                                       final StatefulsetCache statefulsetCache,
                                       final TaskCache taskCache,
                                       final MeterRegistry meterRegistry) {
        super(reconcilierObserver);
        this.context = context;
        this.pluginRegistry = pluginRegistry;
        this.cqlRoleManager = cqlRoleManager;
        this.checkPointCache = checkPointCache;
        this.dataCenterCache = dataCenterCache;
        this.elassandraNodeStatusCache = elassandraNodeStatusCache;
        this.podCache = podCache;
        this.deploymentCache = deploymentCache;
        this.sidecarConnectionCache = sidecarConnectionCache;
        this.statefulsetCache = statefulsetCache;
        this.taskCache = taskCache;
        this.meterRegistry = meterRegistry;
    }
    
    @Override
    public Completable reconcile(final DataCenter dataCenter) throws Exception {
        final CqlSessionHandler cqlSessionHandler = context.createBean(CqlSessionHandler.class, this.cqlRoleManager);
        meterRegistry.counter("datacenter.delete").increment();
        return reconcilierObserver.onReconciliationBegin()
                .andThen(pluginRegistry.deleteAll(dataCenter))
                .andThen(context.createBean(DataCenterDeleteAction.class, dataCenter).deleteDataCenter(cqlSessionHandler))
                .doOnError(t -> { // TODO au lieu de faire le deleteDC en premier ne faut-il pas faire une action deleteDC sur erreur ou simplement logguer les erreur de deletePlugin ???
                    logger.warn("An error occured during delete datacenter action : {} ", t.getMessage(), t);
                    if (!(t instanceof ReconcilierShutdownException)) {
                        reconcilierObserver.failedReconciliationAction();
                    }
                })
                .doOnComplete(reconcilierObserver.endReconciliationAction());
    }
}
