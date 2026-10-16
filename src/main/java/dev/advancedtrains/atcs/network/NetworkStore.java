package dev.advancedtrains.atcs.network;

import dev.advancedtrains.atcs.util.Files2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Owns {@code network.yml}: the current network snapshot, loading, and saving edits.
 *
 * <p>Two rules protect hand-edited files. A file that loaded with errors is never written back,
 * because entries that failed to parse would be lost. And an edit is refused if the file changed on
 * disk since it was loaded, so commands never overwrite edits that have not been reloaded yet.
 */
public final class NetworkStore {
    private final Path file;
    private final NetworkCodec codec = new NetworkCodec();
    private final List<Consumer<Network>> listeners = new CopyOnWriteArrayList<>();
    private volatile Network network = Network.EMPTY;
    private List<String> loadErrors = List.of();
    private String knownContent;

    public NetworkStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public static final class EditRejectedException extends Exception {
        public EditRejectedException(String message) {
            super(message);
        }
    }

    public Path file() {
        return file;
    }

    public Network network() {
        return network;
    }

    /** Errors from the last load. While non-empty the file is read-only for commands. */
    public synchronized List<String> loadErrors() {
        return loadErrors;
    }

    public synchronized boolean writable() {
        return loadErrors.isEmpty();
    }

    public void addListener(Consumer<Network> listener) {
        listeners.add(listener);
    }

    /**
     * Loads the file, creating an empty one if it does not exist. On errors the previous network
     * stays active unless it is the first load, in which case the parsed (partial) network is used.
     */
    public synchronized List<String> load() throws IOException {
        if (!Files.exists(file)) {
            String empty = codec.write(Network.EMPTY);
            Files2.writeAtomically(file, empty);
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        NetworkCodec.ReadResult result = codec.read(content);
        boolean firstLoad = knownContent == null;
        loadErrors = result.errors();
        knownContent = content;
        if (result.clean() || firstLoad) {
            replace(result.network());
        }
        return loadErrors;
    }

    /** Applies an edit and saves it. */
    public synchronized Network edit(UnaryOperator<Network> change) throws EditRejectedException, IOException {
        if (!loadErrors.isEmpty()) {
            throw new EditRejectedException(file.getFileName() + " has errors and is read-only until they are fixed"
                    + " and /atcs reload succeeds: " + loadErrors.get(0));
        }
        String onDisk = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (!Objects.equals(onDisk, knownContent)) {
            throw new EditRejectedException(file.getFileName()
                    + " was changed on disk since it was loaded. Run /atcs reload first, then repeat the command.");
        }
        Network updated = change.apply(network);
        String content = codec.write(updated);
        if (knownContent != null && !knownContent.equals(content)) {
            keep(knownContent);
        }
        Files2.writeAtomically(file, content);
        knownContent = content;
        replace(updated);
        return updated;
    }

    /** How many earlier versions of the file are kept for {@link #undo()}. */
    public static final int HISTORY = 25;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private Path historyDirectory() {
        return file.resolveSibling("history");
    }

    /** Saves the version about to be replaced, so an edit can be undone. */
    private void keep(String previous) throws IOException {
        Path directory = historyDirectory();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("network-" + STAMP.format(Instant.now()) + ".yml"), previous, StandardCharsets.UTF_8);
        List<Path> entries = history();
        for (int index = HISTORY; index < entries.size(); index++) {
            Files.deleteIfExists(entries.get(index));
        }
    }

    /** Earlier versions, newest first. */
    public synchronized List<Path> history() throws IOException {
        Path directory = historyDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().startsWith("network-") && path.toString().endsWith(".yml"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        }
    }

    /**
     * Restores the newest earlier version. The version undone is kept as {@code undone-*.yml} in
     * the history folder in case it is wanted back.
     */
    public synchronized Network undo() throws EditRejectedException, IOException {
        List<Path> entries = history();
        if (entries.isEmpty()) {
            throw new EditRejectedException("There is nothing to undo: no earlier version of " + file.getFileName() + " is kept");
        }
        Path latest = entries.get(0);
        String content = Files.readString(latest, StandardCharsets.UTF_8);
        NetworkCodec.ReadResult result = codec.read(content);
        if (!result.clean()) {
            throw new EditRejectedException("The earlier version " + latest.getFileName() + " has errors: " + result.errors().get(0));
        }
        String current = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (current != null) {
            Files.writeString(historyDirectory().resolve("undone-" + STAMP.format(Instant.now()) + ".yml"), current, StandardCharsets.UTF_8);
        }
        Files2.writeAtomically(file, content);
        Files.deleteIfExists(latest);
        knownContent = content;
        loadErrors = List.of();
        replace(result.network());
        return result.network();
    }

    private void replace(Network updated) {
        network = updated;
        for (Consumer<Network> listener : listeners) {
            listener.accept(updated);
        }
    }
}
