package dev.advancedtrains.atcs.web;

import de.bluecolored.bluemap.api.BlueMapAPI;
import dev.advancedtrains.atcs.config.AtcsSettings;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The folder the railway website lives in: the page and scripts bundled with the plugin, plus the
 * data files the runtime writes. With no folder configured it is BlueMap's web root under
 * {@code atcs/}, so the site is served next to the map, and a button linking to it is added to
 * BlueMap's page through its API.
 */
public final class WebSite {
    public static final String DATA_FOLDER = "data";
    private static final List<String> PAGE_FILES = List.of(
            "index.html", "app.css", "app.js", "layout.js", "planner.js", "map.js", "bluemap-button.js");
    private static final String BUTTON_SCRIPT = "atcs/bluemap-button.js";

    private final AtcsSettings.Web settings;
    private final Logger logger;
    private final Consumer<BlueMapAPI> onBlueMap = this::blueMapEnabled;
    private final boolean listening;
    private volatile @Nullable Path folder;
    private volatile boolean installed;

    public WebSite(Plugin plugin, AtcsSettings.Web settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        if (!settings.folder().isBlank()) {
            folder = Path.of(settings.folder()).toAbsolutePath().normalize();
            listening = false;
            install();
            return;
        }
        boolean blueMap = plugin.getServer().getPluginManager().getPlugin("BlueMap") != null;
        if (blueMap) {
            BlueMapAPI.onEnable(onBlueMap);
            listening = true;
        } else {
            listening = false;
            logger.info("Website not written: set integrations.web.folder to a folder your web server serves, or install BlueMap");
        }
    }

    private void blueMapEnabled(BlueMapAPI api) {
        folder = api.getWebApp().getWebRoot().resolve("atcs").toAbsolutePath().normalize();
        install();
        if (settings.blueMapButton()) {
            try {
                api.getWebApp().registerScript(BUTTON_SCRIPT);
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not add the Railway button to BlueMap's page", exception);
            }
        }
    }

    /** Copies the bundled page into the folder, replacing older copies. */
    private void install() {
        Path target = folder;
        if (target == null) {
            return;
        }
        try {
            Files.createDirectories(target.resolve(DATA_FOLDER));
            for (String name : PAGE_FILES) {
                try (InputStream in = WebSite.class.getResourceAsStream("/web/" + name)) {
                    if (in == null) {
                        throw new IOException("web/" + name + " is missing from the plugin jar");
                    }
                    Files.copy(in, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            installed = true;
            logger.info("Website written to " + target);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not write the website to " + target, exception);
        }
    }

    /** Whether the folder is known and the page is in place. */
    public boolean ready() {
        return installed;
    }

    public @Nullable Path folder() {
        return folder;
    }

    /** Writes a data file atomically; safe to call from a worker thread. */
    public void write(String name, String content) {
        Path target = folder;
        if (target == null || !installed) {
            return;
        }
        Path file = target.resolve(DATA_FOLDER).resolve(name);
        Path temporary = file.resolveSibling(name + ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException notAtomic) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not write " + file, exception);
        }
    }

    public void close() {
        if (listening) {
            BlueMapAPI.unregisterListener(onBlueMap);
        }
    }
}
