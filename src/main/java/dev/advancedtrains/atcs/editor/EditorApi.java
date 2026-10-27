package dev.advancedtrains.atcs.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.advancedtrains.atcs.fare.FareCalculator;
import dev.advancedtrains.atcs.fare.FareCodec;
import dev.advancedtrains.atcs.fare.FareStore;
import dev.advancedtrains.atcs.fare.FareTable;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.NetworkLint;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.operation.RouteMeasure;
import dev.advancedtrains.atcs.operation.RoutePlanner;
import dev.advancedtrains.atcs.operation.TravelTimes;
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
import java.util.OptionalDouble;
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

    /** The dispatcher's view of the railway and the orders it can give; everything runs on the server thread. */
    public interface Dispatcher {
        /** Trains with their holds and skips, lines, blocks, switch signs and recent events. */
        JsonObject state();

        /** Carries out an order: {@code {action, train, station, line, notice, sign, heading, minutes, reason}}. */
        JsonObject act(JsonObject order) throws ApiException;
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
    private @Nullable Dispatcher dispatcher;
    private RouteMeasure measure = RouteMeasure.UNKNOWN;

    /** Adds the dispatcher once the runtime exists. */
    public void setDispatcher(@Nullable Dispatcher newDispatcher) {
        dispatcher = newDispatcher;
    }

    /** The learned track distances fare previews measure rides with. */
    public void setMeasure(RouteMeasure newMeasure) {
        measure = newMeasure;
    }

    public JsonObject dispatchState() throws ApiException {
        if (dispatcher == null) {
            throw new ApiException(404, "The dispatcher is not available", null);
        }
        return dispatcher.state();
    }

    public JsonObject dispatch(JsonObject order) throws ApiException {
        if (dispatcher == null) {
            throw new ApiException(404, "The dispatcher is not available", null);
        }
        return dispatcher.act(order);
    }
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

    /** Most rides one preview may chain. */
    static final int PREVIEW_RIDES = 20;

    /**
     * Prices a journey with the draft fares, without charging or saving anything. Each ride is
     * {@code {service, fromIndex, toIndex, laps}} along a service of the draft network (or the saved
     * one), boarding and alighting at calling stops; {@code laps} adds whole laps of a circular
     * service. Rides must follow on from each other, at the same station or across a transfer.
     */
    public JsonObject previewFares(JsonObject body) throws ApiException {
        if (fares == null) {
            throw new ApiException(404, "Fares are not available", null);
        }
        if (!body.has("document") || !body.get("document").isJsonObject()) {
            throw new ApiException(400, "No fares to preview", null);
        }
        FareCodec.ReadResult read = fareCodec.read(Documents.toYaml(body.getAsJsonObject("document")));
        if (!read.clean()) {
            JsonObject detail = new JsonObject();
            detail.add("errors", strings(read.errors()));
            throw new ApiException(400, "The fares have " + read.errors().size() + " error(s)", detail);
        }
        Network network = store.network();
        if (body.has("network") && body.get("network").isJsonObject()) {
            NetworkCodec.ReadResult draft = networkCodec.read(Documents.toYaml(body.getAsJsonObject("network")));
            if (!draft.clean()) {
                JsonObject detail = new JsonObject();
                detail.add("errors", strings(draft.errors()));
                throw new ApiException(400, "The network has " + draft.errors().size() + " error(s)", detail);
            }
            network = draft.network();
        }
        JsonArray rides = body.has("rides") && body.get("rides").isJsonArray() ? body.getAsJsonArray("rides") : new JsonArray();
        if (rides.isEmpty() || rides.size() > PREVIEW_RIDES) {
            throw new ApiException(400, "Preview between 1 and " + PREVIEW_RIDES + " rides", null);
        }
        List<FareCalculator.RideLeg> legs = new ArrayList<>();
        boolean distanceKnown = true;
        for (int i = 0; i < rides.size(); i++) {
            String ride = "Ride " + (i + 1) + ": ";
            JsonObject item = rides.get(i).getAsJsonObject();
            ServiceKey key;
            try {
                key = ServiceKey.parse(item.get("service").getAsString());
            } catch (IllegalArgumentException exception) {
                throw new ApiException(400, ride + exception.getMessage(), null);
            }
            Service service = network.service(key)
                    .orElseThrow(() -> new ApiException(400, ride + "there is no service " + key + " in the network", null));
            int from = item.get("fromIndex").getAsInt();
            int to = item.get("toIndex").getAsInt();
            int laps = item.has("laps") ? item.get("laps").getAsInt() : 0;
            if (from < 0 || to < 0 || from >= service.size() || to >= service.size()) {
                throw new ApiException(400, ride + key + " has stops 0 to " + (service.size() - 1), null);
            }
            if (!service.stop(from).stops() || !service.stop(to).stops()) {
                throw new ApiException(400, ride + "board and leave at stops where " + key + " calls", null);
            }
            if (laps < 0 || laps > 10 || laps > 0 && !service.circular()) {
                throw new ApiException(400, ride + (service.circular() ? "extra laps go from 0 to 10" : key + " is not circular, so it has no laps"), null);
            }
            if (to < from && !service.circular()) {
                throw new ApiException(400, ride + key + " only runs forward; ride its return service instead", null);
            }
            String start = service.stop(from).stationId();
            if (!legs.isEmpty()) {
                String end = legs.getLast().to();
                if (!end.equals(start) && network.transfer(end, start).isEmpty()) {
                    throw new ApiException(400, ride + "starts at " + network.stationName(start) + ", but the ride before ends at "
                            + network.stationName(end) + " with no transfer between them", null);
                }
            }
            int steps = Math.floorMod(to - from, service.size()) + laps * service.size();
            List<String> path = new ArrayList<>(List.of(start));
            int stops = 0;
            double blocks = 0;
            for (int step = 0, cursor = from; step < steps; step++) {
                OptionalDouble distance = measure.segmentBlocks(service, cursor);
                if (distance.isPresent() && Double.isFinite(distance.getAsDouble()) && distance.getAsDouble() > 0) {
                    blocks += distance.getAsDouble();
                } else {
                    distanceKnown = false;
                }
                cursor = service.nextIndex(cursor);
                path.add(service.stop(cursor).stationId());
                if (service.stop(cursor).stops()) {
                    stops++;
                }
            }
            legs.add(new FareCalculator.RideLeg(key.lineId(), start, path.getLast(), stops, blocks, path));
        }
        FareTable table = read.table();
        FareCalculator.Quote quote = new FareCalculator(table, network, RoutePlanner.of(network, new TravelTimes(0.3), measure))
                .quoteRide(legs);
        JsonObject result = new JsonObject();
        result.addProperty("fare", quote.fare());
        result.addProperty("formatted", table.format(quote.fare()));
        result.addProperty("currency", table.currency());
        result.addProperty("layer", quote.layer().name().toLowerCase(java.util.Locale.ROOT));
        result.add("reasoning", strings(quote.reasoning()));
        result.addProperty("distanceKnown", distanceKnown);
        return result;
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
