package com.strapdata.strapkop.plugins;

import com.google.common.collect.ImmutableList;

import javax.inject.Singleton;
import java.util.List;

@Singleton
public class PluginRegistry {

    final List<Plugin> plugins;

    public PluginRegistry(final ReaperPlugin reaperPlugin, final KibanaPlugin kibanaPlugin) {
        this.plugins = ImmutableList.of(reaperPlugin, kibanaPlugin);
    }

    public List<Plugin> plugins() {
        return this.plugins;
    }
}
