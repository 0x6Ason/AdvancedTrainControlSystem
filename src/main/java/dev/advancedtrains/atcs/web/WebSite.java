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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * The folder the railway website lives in: the page and scripts bundled with the plugin, plus the
 * data files the runtime writes. With no folder configured it is BlueMap's web root under
 * {@code atcs/}, so the site is served next to the map, and a plaque linking to it is added to
 * BlueMap's page through its API.
 *
 * <p>Web servers such as BlueMap's let browsers keep every file for a day. The entry points
 * ({@code index.html} and {@code bluemap-button.js}) therefore never change from one version to
 * the next: they read {@code version.json}, which they fetch past the cache, and load the other
 * files stamped with that version.
 */
public final class WebSite {
    public static final String DATA_FOLDER = "data";
    private static final List<String> PAGE_FILES = List.of(
            "index.html", "app.css", "app.js", "layout.js", "planner.js", "map.js", "bluemap-button.js", "plaque.js");
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
                logger.log(Level.WARNING, "Could not add the Railway plaque to BlueMap's page", exception);
            }
        }
    }

    /** Copies the bundled page into the folder, replacing older copies, and stamps the version. */
    private void install() {
        Path target = folder;
        if (target == null) {
            return;
        }
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            CRC32 checksum = new CRC32();
            for (String name : PAGE_FILES) {
                try (InputStream in = WebSite.class.getResourceAsStream("/web/" + name)) {
                    if (in == null) {
                        throw new IOException("web/" + name + " is missing from the plugin jar");
                    }
                    byte[] bytes = in.readAllBytes();
                    files.put(name, bytes);
                    checksum.update(bytes);
                }
            }
            Files.createDirectories(target.resolve(DATA_FOLDER));
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Files.write(target.resolve(file.getKey()), file.getValue());
            }
            String version = Long.toHexString(checksum.getValue()).toLowerCase(Locale.ROOT);
            writeAtomically(target.resolve("version.json"), "{\"version\":\"" + version + "\"}");
            installed = true;
            logger.info("Website written to " + target + " (version " + version + ")");
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
        try {
            writeAtomically(file, content);
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not write " + file, exception);
        }
    }

    private static void writeAtomically(Path file, String content) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException notAtomic) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void close() {
        if (listening) {
            BlueMapAPI.unregisterListener(onBlueMap);
        }
    }
}
