package dev.advancedtrains.atcs.fare;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.advancedtrains.atcs.util.Files2;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Journeys in progress and the fares charged for them, in {@code fare-ledger.json}. This is account
 * data, not a cache: a file that cannot be read stops automatic fares instead of being replaced.
 */
public final class BillingLedger {
    public static final int FORMAT_VERSION = 1;

    /** DUE: not paid, safe to collect. PROCESSING: being collected. UNCERTAIN: needs checking by hand. */
    public enum Status { DUE, PROCESSING, PAID, UNCERTAIN, WAIVED }

    /**
     * A journey in progress.
     *
     * @param train     the train the rider is on, or {@code null} between trains
     * @param lastIndex the stop index of {@code service} the rider was last carried to, -1 before the first
     * @param expiresAt between trains: when the journey ends unless the rider boards again
     * @param tariff    key into {@link State#tariffs()}: the fares in force when the journey began
     */
    public record Ride(String id, UUID player, String name, @Nullable String train, String service, int lastIndex,
                       long visitAt, long startedAt, long expiresAt, String tariff, List<FareCalculator.RideLeg> legs) {
        public Ride {
            legs = List.copyOf(legs);
        }

        public String origin() {
            return legs.getFirst().from();
        }

        public String destination() {
            return legs.getLast().to();
        }
    }

    public record Charge(String id, UUID player, String name, String from, String to, String currency,
                         double amount, Status status, long at) {
        public Charge withStatus(Status next, long now) {
            return new Charge(id, player, name, from, to, currency, amount, next, now);
        }

        public boolean outstanding() {
            return status == Status.DUE || status == Status.PROCESSING || status == Status.UNCERTAIN;
        }
    }

    public record State(int version, Map<String, FareTable> tariffs, Map<UUID, Ride> rides, Map<String, Charge> charges) {
        public State {
            tariffs = new LinkedHashMap<>(tariffs == null ? Map.of() : tariffs);
            rides = new LinkedHashMap<>(rides == null ? Map.of() : rides);
            charges = new LinkedHashMap<>(charges == null ? Map.of() : charges);
        }
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public BillingLedger(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public State load() throws IOException {
        if (!Files.exists(file)) {
            return new State(FORMAT_VERSION, Map.of(), Map.of(), Map.of());
        }
        try {
            State state = JSON.fromJson(Files.readString(file), State.class);
            if (state == null || state.version() != FORMAT_VERSION) {
                throw new IllegalArgumentException("unknown ledger format");
            }
            state.rides().forEach((player, ride) -> {
                if (ride == null || !player.equals(ride.player()) || ride.id() == null || ride.service() == null
                        || ride.legs().isEmpty() || !state.tariffs().containsKey(ride.tariff())) {
                    throw new IllegalArgumentException("invalid journey for " + player);
                }
            });
            state.charges().forEach((id, charge) -> {
                if (charge == null || !id.equals(charge.id()) || charge.player() == null || charge.status() == null
                        || !Double.isFinite(charge.amount()) || charge.amount() < 0) {
                    throw new IllegalArgumentException("invalid charge " + id);
                }
            });
            return state;
        } catch (RuntimeException exception) {
            throw new IOException("Cannot read " + file.getFileName() + " (" + exception.getMessage()
                    + "); automatic fares are stopped and the file was left as it is", exception);
        }
    }

    /** With {@code durable}, returns only once the file is on disk, as a payment intent must be before money moves. */
    public void save(State state, boolean durable) throws IOException {
        Files2.writeAtomically(file, JSON.toJson(state));
        if (durable) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
    }
}
