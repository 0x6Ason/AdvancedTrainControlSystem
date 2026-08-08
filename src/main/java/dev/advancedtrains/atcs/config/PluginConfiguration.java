package dev.advancedtrains.atcs.config;

import dev.advancedtrains.atcs.persistence.DatabaseConfiguration;
import dev.advancedtrains.atcs.runtime.OperatingMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record PluginConfiguration(
        OperatingMode operatingMode,
        DatabaseConfiguration database,
        boolean receiverSignsEnabled,
        boolean announcementsEnabled,
        boolean logRenderedAnnouncements,
        LegacyReviewConfiguration legacyReview,
        InfrastructureDiscoveryConfiguration infrastructureDiscovery,
        InventoryScanConfiguration inventoryScan,
        PassengerHudConfiguration passengerHud,
        WebConfiguration web) {

    public static PluginConfiguration load(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        FileConfiguration config = plugin.getConfig();
        String filename = config.getString("database.filename", "railcontrol.db");
        Path databasePath = plugin.getDataFolder().toPath().resolve(filename).normalize();
        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        if (!databasePath.toAbsolutePath().startsWith(dataDirectory)) {
            throw new IllegalArgumentException("database.filename must remain inside the plugin data directory");
        }
        LegacyReviewConfiguration legacyReview = new LegacyReviewConfiguration(
                loadActionAliases(config.getConfigurationSection("inventory-review.action-aliases")),
                config.getBoolean("inventory-review.flag-complex-spawn-patterns", true),
                config.getDouble("inventory-review.launch-force.minimum", 0),
                config.getDouble("inventory-review.launch-force.maximum", 0),
                config.getInt("inventory-review.page-size", 8));

        return new PluginConfiguration(
                OperatingMode.parse(config.getString("operating-mode", "READ_ONLY")),
                new DatabaseConfiguration(
                        databasePath,
                        config.getInt("database.busy-timeout-millis", 5000),
                        config.getBoolean("database.backup-before-migration", true)),
                config.getBoolean("integrations.traincarts.receiver-signs-enabled", true),
                config.getBoolean("announcements.enabled", true),
                config.getBoolean("announcements.log-rendered-events", false),
                legacyReview,
                loadInfrastructureDiscovery(config),
                new InventoryScanConfiguration(config.getInt("inventory-scan.chunks-per-tick", 4)),
                new PassengerHudConfiguration(
                        config.getBoolean("passenger-hud.enabled", true),
                        config.getInt("passenger-hud.refresh-ticks", 10),
                        config.getBoolean("passenger-hud.show-unmanaged-trains", true)),
                loadWebConfiguration(config, dataDirectory));
    }

    private static WebConfiguration loadWebConfiguration(
            FileConfiguration config,
            Path dataDirectory) {
        String tokenFilename = config.getString("web.auth.token-file", "web-admin.token");
        Path tokenFile = dataDirectory.resolve(tokenFilename).normalize();
        if (!tokenFile.startsWith(dataDirectory)) {
            throw new IllegalArgumentException("web.auth.token-file must remain inside the plugin data directory");
        }
        return new WebConfiguration(
                config.getBoolean("web.enabled", false),
                config.getString("web.bind-address", "127.0.0.1"),
                config.getInt("web.port", 8765),
                config.getInt("web.backlog", 64),
                config.getInt("web.api-page-size", 100),
                config.getInt("web.network-max-elements", 500000),
                tokenFile,
                config.getInt("web.auth.session-minutes", 30),
                config.getInt("web.auth.login-attempts-per-minute", 5),
                config.getBoolean("web.auth.secure-cookie", false));
    }

    private static InfrastructureDiscoveryConfiguration loadInfrastructureDiscovery(FileConfiguration config) {
        return new InfrastructureDiscoveryConfiguration(
                new InfrastructureDiscoveryConfiguration.StationEvidence(
                        config.getStringList(
                                "infrastructure-discovery.station-evidence.arrival-prefixes"),
                        config.getInt(
                                "infrastructure-discovery.station-evidence.horizontal-radius", 16),
                        config.getInt(
                                "infrastructure-discovery.station-evidence.vertical-tolerance", 4)),
                new InfrastructureDiscoveryConfiguration.StationClassification(
                        config.getBoolean(
                                "infrastructure-discovery.station-classification.platform-requires-arrival-evidence",
                                true),
                        normalizeWords(config.getStringList(
                                "infrastructure-discovery.station-classification.reverse-instructions"),
                                List.of("reverse")),
                        normalizeWords(config.getStringList(
                                "infrastructure-discovery.station-classification.through-instructions"),
                                List.of("continue"))),
                new InfrastructureDiscoveryConfiguration.StationGrouping(
                        config.getInt(
                                "infrastructure-discovery.station-grouping.horizontal-radius", 48),
                        config.getInt(
                                "infrastructure-discovery.station-grouping.vertical-tolerance", 12)),
                new InfrastructureDiscoveryConfiguration.Confidence(
                        config.getDouble(
                                "infrastructure-discovery.confidence.named-station", 0.90),
                        config.getDouble(
                                "infrastructure-discovery.confidence.unnamed-station", 0.40),
                        config.getDouble(
                                "infrastructure-discovery.confidence.departure-point", 0.85),
                        config.getDouble(
                                "infrastructure-discovery.confidence.track-control", 0.70)),
                new InfrastructureDiscoveryConfiguration.Rail(
                        InfrastructureDiscoveryConfiguration.Rail.Scope.valueOf(
                                config.getString(
                                                "infrastructure-discovery.rail.scope",
                                                "SAVED_CHUNKS")
                                        .strip()
                                        .toUpperCase(java.util.Locale.ROOT)),
                        config.getInt(
                                "infrastructure-discovery.rail.chunk-snapshots-per-tick", 2),
                        config.getInt(
                                "infrastructure-discovery.rail.block-checks-per-tick", 16384),
                        config.getInt(
                                "infrastructure-discovery.rail.edge-nodes-per-tick", 4096),
                        config.getInt("infrastructure-discovery.rail.maximum-nodes", 500000)));
    }

    private static Set<String> normalizeWords(List<String> configured, List<String> defaults) {
        List<String> source = configured.isEmpty() ? defaults : configured;
        return source.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static java.util.Map<String, String> loadActionAliases(ConfigurationSection section) {
        if (section == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> aliases = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String target = section.getString(key);
            if (target != null) {
                aliases.put(key, target);
            }
        }
        return aliases;
    }

    public record InventoryScanConfiguration(int chunksPerTick) {
        public InventoryScanConfiguration {
            if (chunksPerTick <= 0) {
                throw new IllegalArgumentException("inventory-scan.chunks-per-tick must be positive");
            }
        }
    }

    public record PassengerHudConfiguration(boolean enabled, int refreshTicks, boolean showUnmanagedTrains) {
        public PassengerHudConfiguration {
            if (refreshTicks <= 0) {
                throw new IllegalArgumentException("passenger-hud.refresh-ticks must be positive");
            }
        }
    }
}
