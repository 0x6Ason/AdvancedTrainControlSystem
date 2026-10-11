package dev.advancedtrains.atcs.importer;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.operation.RouteSuggester;
import dev.advancedtrains.atcs.operation.TripLog;
import dev.advancedtrains.atcs.util.Files2;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The import workflow. Stations are drafted from signs in loaded chunks (scans can be repeated in
 * different areas and accumulate); lines and services are suggested from the trips trains are seen
 * making. Both go into one proposal file that an operator reviews before applying.
 */
public final class ImportService {
    private final Plugin plugin;
    private final NetworkStore store;
    private final Supplier<List<TripLog.Trip>> trips;
    private final Path proposalFile;
    private final SignScanner scanner = new SignScanner();
    private final Map<String, ScannedSign> collected = new LinkedHashMap<>();
    private int scansCompleted;
    private StationImporter.Settings settings;
    private int chunksPerTick;

    public ImportService(Plugin plugin, NetworkStore store, Supplier<List<TripLog.Trip>> trips,
                         StationImporter.Settings settings, int chunksPerTick) {
        this.plugin = plugin;
        this.store = store;
        this.trips = trips;
        this.proposalFile = plugin.getDataFolder().toPath().resolve("import-proposal.yml");
        this.settings = settings;
        this.chunksPerTick = chunksPerTick;
    }

    public void configure(StationImporter.Settings newSettings, int newChunksPerTick) {
        this.settings = newSettings;
        this.chunksPerTick = newChunksPerTick;
    }

    public boolean scanning() {
        return scanner.running();
    }

    /** Scans loaded chunks of the given worlds and adds the signs found to the collection. */
    public void scan(Collection<World> worlds, Consumer<SignScanner.Result> done) {
        scanner.start(plugin, worlds, chunksPerTick, result -> {
            for (ScannedSign sign : result.signs()) {
                collected.put(sign.position().format() + (sign.front() ? ":front" : ":back"), sign);
            }
            scansCompleted++;
            plugin.getLogger().info("Import scan read " + result.chunksScanned() + " loaded chunks and found "
                    + result.signs().size() + " signs (" + collected.size() + " collected so far)");
            done.accept(result);
        });
    }

    public int collectedSigns() {
        return collected.size();
    }

    /** Number of finished scans since the last reset. */
    public int scansCompleted() {
        return scansCompleted;
    }

    /** Stations drafted from the scanned signs, or {@code null} before the first scan. */
    public StationImporter.@Nullable Proposal stations() {
        return scansCompleted == 0 ? null : new StationImporter(settings).propose(collected.values(), store.network());
    }

    /**
     * Lines and services suggested from observed trips. Signs collected by scans, if any, help name
     * the lines.
     */
    public RouteSuggester.Suggestion services(int minObservations) {
        Network network = store.network();
        StationImporter importer = new StationImporter(settings);
        return RouteSuggester.suggest(trips.get(), network, minObservations, platform -> network
                .station(platform.stationId())
                .flatMap(station -> station.platform(platform.platformId()))
                .map(found -> found.triggers().stream()
                        .flatMap(trigger -> importer.nearbyTexts(trigger, collected.values()).stream())
                        .toList())
                .orElse(List.of()));
    }

    public Path proposalFile() {
        return proposalFile;
    }

    /** Writes the station draft (if anything was scanned) and the service suggestions. */
    public void writeProposal(StationImporter.@Nullable Proposal stations, RouteSuggester.@Nullable Suggestion services)
            throws IOException {
        Files2.writeAtomically(proposalFile, ProposalFile.write(stations, services, Instant.now()));
    }

    /**
     * Merges the reviewed proposal file into the network.
     *
     * @throws IllegalArgumentException when the file is missing or invalid
     */
    public ProposalMerger.Result apply() throws IOException, NetworkStore.EditRejectedException {
        if (!Files.exists(proposalFile)) {
            throw new IllegalArgumentException("There is no import-proposal.yml yet; run /atcs import write first");
        }
        ProposalFile.Contents contents = ProposalFile.read(Files.readString(proposalFile, StandardCharsets.UTF_8));
        ProposalMerger.Result[] result = new ProposalMerger.Result[1];
        store.edit(network -> {
            result[0] = ProposalMerger.merge(network, contents);
            return result[0].network();
        });
        return result[0];
    }

    public void reset() {
        scanner.cancel();
        collected.clear();
        scansCompleted = 0;
    }

    public void close() {
        scanner.cancel();
    }
}
