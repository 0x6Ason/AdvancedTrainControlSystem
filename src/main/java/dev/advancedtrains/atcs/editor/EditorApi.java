package dev.advancedtrains.atcs.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.advancedtrains.atcs.fare.FareCodec;
import dev.advancedtrains.atcs.fare.FareStore;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.NetworkLint;
import dev.advancedtrains.atcs.network.NetworkStore;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the editor can do, as plain operations on JSON documents: read network.yml and fares.yml
 * as documents, validate a changed document with the same codecs and messages the files get,
 * save it through the stores (so a hand edit on disk is never clobbered), undo, save the diagram
 * layout, and edit the configuration files. {@link EditorServer} puts this behind HTTP; the
 * operations themselves run on the server thread.
 */
public final class EditorApi {

    /** A refused request: the HTTP status to answer with, a message, and details for the page. */
    public static final class ApiException extends Exception {
        private final int status;
        private final @Nullable JsonObject detail;

        public ApiException(int status, String message, @Nullable JsonObject detail) {
            super(message);
            this.status = status;
            this.detail = detail;
        }

        public int status() {
            return status;
        }

        public @Nullable JsonObject detail() {
            return detail;
        }
    }

    /** Writes the diagram layout the editor sends. */
    @FunctionalInterface
    public interface LayoutSaver {
        void save(Map<String, List<Integer>> stations, @Nullable List<Object> arrangement) throws IOException;
    }

    /** The configuration files the editor may edit, by name, and the reload that applies them. */
    public interface ConfigFiles {
        List<String> names();

        Path path(String name) throws ApiException;

        /** Applies the files; returns problems to report. */
        List<String> reload();
    }

    private final NetworkStore store;
    private final @Nullable FareStore fares;
    private final Function<Network, List<NetworkLint.Finding>> lint;
    private final Supplier<String> live;
    private final LayoutSaver layouts;
    private final ConfigFiles configs;
    private final Function<Network, JsonObject> context;
    private final BiConsumer<Network, Network> saved;
    private final NetworkCodec networkCodec = new NetworkCodec();
    private final FareCodec fareCodec = new FareCodec();

    public EditorApi(NetworkStore store, @Nullable FareStore fares, Function<Network, List<NetworkLint.Finding>> lint,
                     Supplier<String> live, LayoutSaver layouts, ConfigFiles configs) {
        this(store, fares, lint, live, layouts, configs, network -> new JsonObject(), (before, after) -> {
        });
    }

    /**
     * @param context what the page needs beside the file: station positions, short codes, the saved
     *                diagram layout, the site title
     * @param saved   called after a save with the network before and after, to carry learned data over
     */
    public EditorApi(NetworkStore store, @Nullable FareStore fares, Function<Network, List<NetworkLint.Finding>> lint,
                     Supplier<String> live, LayoutSaver layouts, ConfigFiles configs,
                     Function<Network, JsonObject> context, BiConsumer<Network, Network> saved) {
        this.store = store;
        this.fares = fares;
        this.lint = lint;
        this.live = live;
        this.layouts = layouts;
        this.configs = configs;
        this.context = context;
        this.saved = saved;
    }

    /** The versions of the files, so the page notices edits made elsewhere (in game, by hand) cheaply. */
    public JsonObject versions() throws ApiException {
        JsonObject result = new JsonObject();
        result.addProperty("network", version(networkYaml()));
        result.addProperty("writable", store.writable());
        if (fares != null) {
            result.addProperty("fares", version(faresYaml()));
        }
        return result;
    }

    // ---------------------------------------------------------------- network

    /**
     * The network as the editor works on it: the file's structure as JSON, in the form ATCS writes
     * it (so short forms written by hand arrive spelled out), with its state, checks and context.
     * The version is of the file on disk, so a hand edit is noticed.
     */
    public JsonObject network() throws ApiException {
        String onDisk = networkYaml();
        Network network = store.network();
        String canonical = networkCodec.write(network);
        JsonObject result = new JsonObject();
        result.addProperty("version", version(onDisk));
        result.addProperty("writable", store.writable());
        result.add("loadErrors", strings(store.loadErrors()));
        result.add("issues", strings(network.issues()));
        result.add("lint", findings(lint.apply(network)));
        result.add("document", document(canonical));
        result.addProperty("yaml", canonical);
        result.add("context", context.apply(network));
        return result;
    }

    /** Checks a changed document without saving: codec errors, network issues, lint, and the YAML it would write. */
    public JsonObject validate(JsonObject document) {
        JsonObject result = new JsonObject();
        NetworkCodec.ReadResult read = networkCodec.read(Documents.toYaml(document));
        result.add("errors", strings(read.errors()));
        result.add("issues", strings(read.network().issues()));
        result.add("lint", findings(read.errors().isEmpty() ? lint.apply(read.network()) : List.of()));
        result.addProperty("yaml", networkCodec.write(read.network()));
        return result;
    }

    /**
     * Saves a document: refused with the current state when the editor's base version is stale,
     * when the codec finds errors, or when the file changed on disk.
     */
    public JsonObject saveNetwork(@Nullable String base, JsonObject document) throws ApiException {
        String current = version(networkYaml());
        if (base != null && !base.equals(current)) {
            throw new ApiException(409, "The network changed since you loaded it; your edits are kept on this page, reload to see the new state", network());
        }
        NetworkCodec.ReadResult read = networkCodec.read(Documents.toYaml(document));
        if (!read.clean()) {
            JsonObject detail = new JsonObject();
            detail.add("errors", strings(read.errors()));
            throw new ApiException(400, "The network has " + read.errors().size() + " error(s)", detail);
        }
        Network before = store.network();
        try {
            Network after = store.edit(ignored -> read.network());
            saved.accept(before, after);
        } catch (NetworkStore.EditRejectedException exception) {
            throw new ApiException(409, exception.getMessage(), network());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(400, exception.getMessage(), null);
        } catch (IOException exception) {
            throw new ApiException(500, "Could not save network.yml: " + exception.getMessage(), null);
        }
        return network();
    }

    public JsonObject undo() throws ApiException {
        try {
            store.undo();
        } catch (NetworkStore.EditRejectedException exception) {
            throw new ApiException(409, exception.getMessage(), null);
        } catch (IOException exception) {
            throw new ApiException(500, exception.getMessage(), null);
        }
        return network();
    }

    private String networkYaml() throws ApiException {
        try {
            return Files.exists(store.file()) ? Files.readString(store.file(), StandardCharsets.UTF_8) : networkCodec.write(store.network());
        } catch (IOException exception) {
            throw new ApiException(500, "Could not read network.yml: " + exception.getMessage(), null);
        }
    }

    // ---------------------------------------------------------------- fares

    public JsonObject fares() throws ApiException {
        if (fares == null) {
            throw new ApiException(404, "Fares are not available", null);
        }
        String onDisk = faresYaml();
        String canonical = fareCodec.write(fares.table());
        JsonObject result = new JsonObject();
        result.addProperty("version", version(onDisk));
        result.add("loadErrors", strings(fares.loadErrors()));
        result.add("document", document(canonical));
        result.addProperty("yaml", canonical);
        result.add("stations", strings(new ArrayList<>(store.network().stations().keySet())));
        return result;
    }

    public JsonObject validateFares(JsonObject document) throws ApiException {
        if (fares == null) {
            throw new ApiException(404, "Fares are not available", null);
        }
        FareCodec.ReadResult read = fareCodec.read(Documents.toYaml(document));
        JsonObject result = new JsonObject();
        result.add("errors", strings(read.errors()));
        result.addProperty("yaml", fareCodec.write(read.table()));
        return result;
    }

    public JsonObject saveFares(@Nullable String base, JsonObject document) throws ApiException {
        if (fares == null) {
            throw new ApiException(404, "Fares are not available", null);
        }
        String current = version(faresYaml());
        if (base != null && !base.equals(current)) {
            throw new ApiException(409, "The fares changed since you loaded them; reload to see the new state", fares());
        }
        FareCodec.ReadResult read = fareCodec.read(Documents.toYaml(document));
        if (!read.clean()) {
            JsonObject detail = new JsonObject();
            detail.add("errors", strings(read.errors()));
            throw new ApiException(400, "The fares have " + read.errors().size() + " error(s)", detail);
        }
        try {
            fares.edit(ignored -> read.table());
        } catch (IOException exception) {
            throw new ApiException(409, exception.getMessage(), fares());
        }
        return fares();
    }

    private String faresYaml() throws ApiException {
        try {
            return fares != null && Files.exists(fares.file()) ? Files.readString(fares.file(), StandardCharsets.UTF_8)
                    : fareCodec.write(fares == null ? FareTable.EMPTY : fares.table());
        } catch (IOException exception) {
            throw new ApiException(500, "Could not read fares.yml: " + exception.getMessage(), null);
        }
    }

    // ---------------------------------------------------------------- layout, live, config

    /** Saves {@code {stations: {id: [column, row]}, arrangement: [...]}} as the diagram layout. */
    public JsonObject saveLayout(JsonObject layout) throws ApiException {
        Map<String, List<Integer>> stations = new java.util.LinkedHashMap<>();
        JsonObject cells = layout.has("stations") && layout.get("stations").isJsonObject() ? layout.getAsJsonObject("stations") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : cells.entrySet()) {
            JsonElement cell = entry.getValue();
            if (cell.isJsonArray() && cell.getAsJsonArray().size() == 2) {
                stations.put(entry.getKey(), List.of(cell.getAsJsonArray().get(0).getAsInt(), cell.getAsJsonArray().get(1).getAsInt()));
            }
        }
        List<Object> arrangement = null;
        if (layout.has("arrangement") && layout.get("arrangement").isJsonArray()) {
            arrangement = new ArrayList<>();
            for (JsonElement item : layout.getAsJsonArray("arrangement")) {
                arrangement.add(Documents.plain(item));
            }
        }
        try {
            layouts.save(stations, arrangement);
        } catch (IOException exception) {
            throw new ApiException(500, "Could not save the layout: " + exception.getMessage(), null);
        }
        JsonObject result = new JsonObject();
        result.addProperty("saved", stations.size());
        return result;
    }

    public String live() {
        return live.get();
    }

    public JsonObject configNames() {
        JsonObject result = new JsonObject();
        result.add("files", strings(configs.names()));
        return result;
    }

    public JsonObject config(String name) throws ApiException {
        Path path = configs.path(name);
        JsonObject result = new JsonObject();
        result.addProperty("name", name);
        try {
            String text = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
            result.addProperty("text", text);
            result.addProperty("version", version(text));
        } catch (IOException exception) {
            throw new ApiException(500, "Could not read " + name + ": " + exception.getMessage(), null);
        }
        return result;
    }

    /** Writes a configuration file after checking it parses, then reloads; returns the reload's problems. */
    public JsonObject saveConfig(String name, @Nullable String base, String text) throws ApiException {
        Path path = configs.path(name);
        try {
            String current = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
            if (base != null && !base.equals(version(current))) {
                throw new ApiException(409, name + " changed since you loaded it; reload to see the new state", config(name));
            }
            new YamlConfiguration().loadFromString(text);
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (InvalidConfigurationException exception) {
            throw new ApiException(400, "Not valid YAML: " + exception.getMessage(), null);
        } catch (IOException exception) {
            throw new ApiException(500, "Could not write " + name + ": " + exception.getMessage(), null);
        }
        JsonObject result = config(name);
        result.add("problems", strings(configs.reload()));
        return result;
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject document(String yaml) throws ApiException {
        try {
            return Documents.fromYaml(yaml);
        } catch (InvalidConfigurationException exception) {
            throw new ApiException(500, "The file is not valid YAML: " + exception.getMessage(), null);
        }
    }

    /** A short fingerprint of a file's text, so a stale editor cannot overwrite a newer file. */
    public static String version(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Objects.requireNonNullElse(text, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static JsonArray findings(List<NetworkLint.Finding> findings) {
        JsonArray array = new JsonArray();
        for (NetworkLint.Finding finding : findings) {
            JsonObject entry = new JsonObject();
            entry.addProperty("level", finding.level().name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("text", finding.text());
            array.add(entry);
        }
        return array;
    }
}
