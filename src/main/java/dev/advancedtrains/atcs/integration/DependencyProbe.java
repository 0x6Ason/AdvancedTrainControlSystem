package dev.advancedtrains.atcs.integration;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Objects;
import java.util.Optional;

public final class DependencyProbe {
    private final PluginManager pluginManager;

    public DependencyProbe(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager, "pluginManager");
    }

    public DependencyStatus inspect(String pluginName) {
        Plugin plugin = pluginManager.getPlugin(pluginName);
        if (plugin == null || !plugin.isEnabled()) {
            return new DependencyStatus(pluginName, false, Optional.empty());
        }
        return new DependencyStatus(
                pluginName,
                true,
                Optional.of(plugin.getPluginMeta().getVersion()));
    }
}

