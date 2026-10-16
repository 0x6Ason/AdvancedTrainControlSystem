package dev.advancedtrains.atcs.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Brings a configuration file on disk up to the bundled one: options the file lacks are added
 * with their comments and defaults, renamed options move, and the operator's own values stay
 * exactly as they were. The file is rewritten only when something changed, after a backup next
 * to it, so an update never leaves a half-valid file and never loses a setting.
 */
public final class ConfigUpgrader {
    /** Old option path to new, applied to files older than the current version. */
    private static final Map<String, String> RENAMES = Map.of();

    /** What the upgrade did. */
    public record Report(String file, int fromVersion, int toVersion, List<String> added, List<String> renamed,
                         List<String> unknown, boolean written, @Nullable Path backup, @Nullable String problem) {
        public boolean changed() {
            return written;
        }

        /** One line for the console. */
        public String summary() {
            if (problem != null) {
                return file + " was left alone: " + problem;
            }
            if (!written) {
                return file + " is current";
            }
            StringBuilder text = new StringBuilder(file);
            if (toVersion > 0 && fromVersion != toVersion) {
                text.append(" upgraded from version ").append(fromVersion).append(" to ").append(toVersion);
            } else {
                text.append(" updated");
            }
            if (!added.isEmpty()) {
                text.append("; ").append(added.size()).append(added.size() == 1 ? " option added: " : " options added: ")
                        .append(String.join(", ", added.size() > 8 ? added.subList(0, 8) : added))
                        .append(added.size() > 8 ? ", ..." : "");
            }
            if (!renamed.isEmpty()) {
                text.append("; renamed ").append(String.join(", ", renamed));
            }
            if (backup != null) {
                text.append("; the old file is ").append(backup.getFileName());
            }
            return text.toString();
        }
    }

    private ConfigUpgrader() {
    }

    /**
     * Upgrades {@code file} to the layout of {@code bundledYaml}.
     *
     * @param currentVersion the value {@code config-version} should have, or -1 for a file without one
     */
    public static Report upgrade(Path file, String bundledYaml, int currentVersion, Logger logger) throws IOException {
        String name = file.getFileName().toString();
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.options().parseComments(true);
        YamlConfiguration user = new YamlConfiguration();
        user.options().parseComments(true);
        try {
            bundled.loadFromString(bundledYaml);
        } catch (InvalidConfigurationException exception) {
            throw new IOException("The bundled " + name + " is not valid YAML", exception);
        }
        if (!Files.exists(file)) {
            Files.writeString(file, bundledYaml, StandardCharsets.UTF_8);
            Report report = new Report(name, currentVersion, currentVersion, List.of(), List.of(), List.of(), true, null, null);
            logger.info(name + " written with the defaults");
            return report;
        }
        try {
            user.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            Report report = new Report(name, -1, currentVersion, List.of(), List.of(), List.of(), false, null,
                    "it is not valid YAML (" + firstLine(exception.getMessage()) + "); fix it and /atcs reload");
            logger.warning(report.summary());
            return report;
        }
        int fromVersion = currentVersion < 0 ? -1 : user.getInt("config-version", 0);

        List<String> renamed = new ArrayList<>();
        if (currentVersion >= 0 && fromVersion < currentVersion) {
            for (Map.Entry<String, String> rename : RENAMES.entrySet()) {
                if (user.isSet(rename.getKey()) && !user.isSet(rename.getValue())) {
                    user.set(rename.getValue(), user.get(rename.getKey()));
                    user.set(rename.getKey(), null);
                    renamed.add(rename.getKey() + " -> " + rename.getValue());
                }
            }
        }

        // The bundled file supplies the order and the comments; the operator's values go in over it.
        List<String> added = new ArrayList<>();
        for (String key : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(key) || key.equals("config-version")) {
                continue;
            }
            if (user.isSet(key) && !user.isConfigurationSection(key)) {
                bundled.set(key, user.get(key));
            } else {
                added.add(key);
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String key : user.getKeys(true)) {
            if (user.isConfigurationSection(key) || bundled.isSet(key) || key.equals("config-version")) {
                continue;
            }
            unknown.add(key);
            bundled.set(key, user.get(key));
        }
        if (currentVersion >= 0) {
            bundled.set("config-version", currentVersion);
        }

        boolean changed = !added.isEmpty() || !renamed.isEmpty() || (currentVersion >= 0 && fromVersion != currentVersion);
        Path backup = null;
        if (changed) {
            backup = file.resolveSibling(name + (fromVersion >= 0 ? ".v" + fromVersion : "") + ".bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(file, bundled.saveToString(), StandardCharsets.UTF_8);
        }
        Report report = new Report(name, fromVersion, currentVersion, List.copyOf(added), List.copyOf(renamed),
                List.copyOf(unknown), changed, backup, null);
        if (changed) {
            logger.info(report.summary());
        }
        for (String key : unknown) {
            logger.warning(name + ": '" + key + "' is not an option ATCS knows; it is kept, check the spelling");
        }
        return report;
    }

    private static String firstLine(@Nullable String message) {
        if (message == null) {
            return "syntax error";
        }
        int end = message.indexOf('\n');
        return (end < 0 ? message : message.substring(0, end)).trim();
    }
}
