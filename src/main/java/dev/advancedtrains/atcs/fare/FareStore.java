package dev.advancedtrains.atcs.fare;

import dev.advancedtrains.atcs.util.Files2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Owns {@code fares.yml}: loads it, writes it atomically, and refuses an edit when the file changed
 * on disk since it was loaded, so a hand edit is never overwritten.
 */
public final class FareStore {
    private final Path file;
    private final FareCodec codec = new FareCodec();
    private volatile FareTable table = FareTable.EMPTY;
    private List<String> loadErrors = List.of();
    private String knownContent;

    public FareStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public FareTable table() {
        return table;
    }

    public synchronized List<String> loadErrors() {
        return loadErrors;
    }

    /** Loads the file, creating a template if it does not exist. Returns the errors found. */
    public synchronized List<String> load() throws IOException {
        if (!Files.exists(file)) {
            Files2.writeAtomically(file, codec.write(FareTable.EMPTY));
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        FareCodec.ReadResult result = codec.read(content);
        boolean firstLoad = knownContent == null;
        loadErrors = result.errors();
        knownContent = content;
        if (result.clean() || firstLoad) {
            table = result.table();
        }
        return loadErrors;
    }

    /** Applies an edit and saves it. */
    public synchronized FareTable edit(UnaryOperator<FareTable> change) throws IOException {
        if (!loadErrors.isEmpty()) {
            throw new IOException(file.getFileName() + " has errors and is read-only until they are fixed and /atcs reload succeeds: "
                    + loadErrors.get(0));
        }
        String onDisk = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (knownContent != null && !Objects.equals(onDisk, knownContent)) {
            throw new IOException(file.getFileName() + " was changed on disk since it was loaded. Run /atcs reload first, then repeat the command.");
        }
        FareTable updated = change.apply(table);
        String content = codec.write(updated);
        Files2.writeAtomically(file, content);
        knownContent = content;
        table = updated;
        return updated;
    }
}
