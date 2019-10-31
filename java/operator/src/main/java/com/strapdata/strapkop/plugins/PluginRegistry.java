package com.strapdata.strapkop.plugins;

import com.google.common.collect.ImmutableList;
import com.strapdata.model.k8s.cassandra.DataCenter;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;

/**
 * List of available plugins
 */
@Singleton
public class PluginRegistry {

    private final Logger logger = LoggerFactory.getLogger(PluginRegistry.class);

    final List<Plugin> plugins;

    public PluginRegistry(final ReaperPlugin reaperPlugin, final KibanaPlugin kibanaPlugin) {
        this.plugins = ImmutableList.of(reaperPlugin, kibanaPlugin);
    }

    public List<Plugin> plugins() {
        return this.plugins;
    }

    public Completable deleteAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for(Plugin plugin : plugins) {
            try {
                pluginCompletables.add(plugin.delete(dc));
            } catch(Exception e) {
                logger.error("Plugin class="+plugin.getClass().getSimpleName()+" reconcilation failed:", e);
            }
        }
        return Completable.mergeArray(pluginCompletables.toArray(new Completable[pluginCompletables.size()]));
    }

    public Completable[] reconcileAll(DataCenter dc) {
        List<Completable> pluginCompletables = new ArrayList<>();
        for(Plugin plugin : plugins) {
            try {
                if (plugin.isActive(dc))
                    pluginCompletables.add(plugin.reconcile(dc));
                else
                    pluginCompletables.add(plugin.delete(dc));
            } catch (Exception e) {
                logger.error("Plugin class=" + plugin.getClass().getSimpleName() + " reconcilation failed:", e);
            }
        }
        return pluginCompletables.toArray(new Completable[pluginCompletables.size()]);
    }

}
