package com.strapdata.strapkop.plugins;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenter;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * Plugin registry
 */
@Singleton
public class PluginRegistry {

    private final Logger logger = LoggerFactory.getLogger(PluginRegistry.class);

    final List<Plugin> plugins;

    public PluginRegistry(final ReaperPlugin reaperPlugin,
                          final KibanaPlugin kibanaPlugin,
                          final TestSuitePlugin testPlugin,
                          final ManagedKeyspacePlugin managedKeyspacePlugin,
                          final WebHookPlugin webHookPlugin) {
        this.plugins = ImmutableList.of(reaperPlugin, kibanaPlugin, testPlugin, managedKeyspacePlugin, webHookPlugin);
    }

    public List<Plugin> plugins() {
        return this.plugins;
    }

    public Completable deleteAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for (Plugin plugin : plugins) {
            try {
                pluginCompletables.add(plugin.delete(dc)
                        .onErrorResumeNext(t -> {
                            logger.warn("plugin={} delete failed, error={}", plugin.getClass().getName(), t.toString());
                            return Completable.complete();
                        }));
            } catch (Exception e) {
                logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconciliation failed:", e);
            }
        }
        return Completable.mergeArray(pluginCompletables.toArray(new Completable[pluginCompletables.size()]));
    }

    public Completable[] reconcileAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for (Plugin plugin : plugins) {
            if (!dc.getSpec().isParked() || plugin.reconcileOnParkState()) {
                try {
                    if (plugin.isActive(dc))
                        pluginCompletables.add(plugin.reconcile(dc)
                                .onErrorResumeNext(t -> {
                                    logger.warn("plugin={} reconcile failed, error={}", plugin.getClass().getName(), t.toString());
                                    return Completable.complete();
                                }));
                    else
                        pluginCompletables.add(plugin.delete(dc)
                                .onErrorResumeNext(t -> {
                                    logger.warn("plugin={} delete failed, error={}", plugin.getClass().getName(), t.toString());
                                    return Completable.complete();
                                }));
                } catch (Exception e) {
                    logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconciliation failed:", e);
                }
            }
        }
        return pluginCompletables.toArray(new Completable[pluginCompletables.size()]);
    }

    public Completable[] reconciledAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for (Plugin plugin : plugins) {
            if (!dc.getSpec().isParked() || plugin.reconcileOnParkState()) {
                try {
                    if (plugin.isActive(dc))
                        pluginCompletables.add(
                                plugin.reconciled(dc)
                                        .onErrorResumeNext(t -> {
                                            logger.warn("plugin={} reconcilied failed, error={}", plugin.getClass().getName(), t.toString());
                                            return Completable.complete();
                                        }));
                } catch (Exception e) {
                    logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconciled failed:", e);
                }
            }
        }
        return pluginCompletables.toArray(new Completable[pluginCompletables.size()]);
    }
}
