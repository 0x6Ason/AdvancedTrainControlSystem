package dev.advancedtrains.atcs.placeholder;

import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkStore;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.TrainView;
import dev.advancedtrains.atcs.runtime.FareService;
import dev.advancedtrains.atcs.runtime.RailwayRuntime;
import dev.advancedtrains.atcs.runtime.TrainHandle;
import dev.advancedtrains.atcs.runtime.TrainSource;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PlaceholderAPI placeholders, all starting with {@code atcs_}.
 *
 * <p>About the player's train: {@code line}, {@code line_short}, {@code service}, {@code destination},
 * {@code next}, {@code eta}, {@code train}, {@code status}, {@code station} (the station the train is
 * at; off a train, the nearest station to the player). About a station's next departure:
 * {@code next_dest_<station>}, {@code next_time_<station>}, {@code next_line_<station>},
 * {@code next_service_<station>}, {@code next_platform_<station>}, with {@code <station>} a station id,
 * optionally {@code <station>:<platform>}; a number after that picks the second or third departure
 * ({@code next_time_main:2_2}). {@code fare_<from>:<to>} gives the configured fare, and
 * {@code station_name_<id>} a station's name.
 */
public final class AtcsPlaceholders extends PlaceholderExpansion {
    private static final int NEAR_BLOCKS = 48;

    private final RailwayRuntime runtime;
    private final TrainSource trains;
    private final NetworkStore store;
    private final Supplier<Messages> messages;
    private final @Nullable FareService fares;
    private final String version;

    public AtcsPlaceholders(RailwayRuntime runtime, TrainSource trains, NetworkStore store, Supplier<Messages> messages,
                            @Nullable FareService fares, String version) {
        this.runtime = runtime;
        this.trains = trains;
        this.store = store;
        this.messages = messages;
        this.fares = fares;
        this.version = version;
    }

    @Override
    public String getIdentifier() {
        return "atcs";
    }

    @Override
    public String getAuthor() {
        return "ATCS";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        String request = params.toLowerCase(Locale.ROOT);
        Network network = store.network();
        if (request.startsWith("next_")) {
            return departure(network, request.substring("next_".length()));
        }
        if (request.startsWith("fare_")) {
            return fare(network, request.substring("fare_".length()));
        }
        if (request.startsWith("station_name_")) {
            return network.station(request.substring("station_name_".length())).map(Station::name).orElse("");
        }
        Player online = player == null ? null : player.getPlayer();
        if (online == null) {
            return "";
        }
        return aboutTrain(network, online, request);
    }

    /** {@code <field>_<station[:platform]>[_<rank>]}. */
    private @Nullable String departure(Network network, String rest) {
        int underscore = rest.indexOf('_');
        if (underscore < 0) {
            return null;
        }
        String field = rest.substring(0, underscore);
        String target = rest.substring(underscore + 1);
        int rank = 1;
        int lastUnderscore = target.lastIndexOf('_');
        if (lastUnderscore > 0) {
            try {
                rank = Integer.parseInt(target.substring(lastUnderscore + 1));
                target = target.substring(0, lastUnderscore);
            } catch (NumberFormatException ignored) {
                // The station id itself contains an underscore.
            }
        }
        String stationId = target;
        String platformId = null;
        int colon = target.indexOf(':');
        if (colon >= 0) {
            stationId = target.substring(0, colon);
            platformId = target.substring(colon + 1);
        }
        if (network.station(stationId).isEmpty()) {
            return null;
        }
        String wanted = platformId;
        List<Departures.Departure> departures = runtime.departures(stationId, platform -> wanted == null || wanted.equals(platform));
        if (departures.size() < rank) {
            return "";
        }
        Departures.Departure departure = departures.get(rank - 1);
        return switch (field) {
            case "dest", "destination" -> departure.service().destinationFrom(departure.stopIndex())
                    .map(stop -> network.stationName(stop.stationId())).orElse(departure.service().name());
            case "time", "eta" -> departure.status() == Departures.Status.BOARDING ? plain(messages.get().render("board.status-boarding"))
                    : departure.late() ? plain(messages.get().render("board.status-delayed"))
                    : departure.etaSeconds().isPresent()
                            ? (departure.approximate() ? "~" : "") + plain(messages.get().duration(departure.etaSeconds().getAsDouble()))
                    : plain(messages.get().render("board.status-unknown"));
            case "line" -> network.line(departure.service().key().lineId()).map(line -> line.shortName()).orElse("");
            case "linename" -> network.line(departure.service().key().lineId()).map(line -> line.name()).orElse("");
            case "service" -> departure.service().name();
            case "platform" -> departure.platformId() == null ? "" : departure.platformId();
            case "train" -> departure.trainId();
            default -> null;
        };
    }

    private @Nullable String fare(Network network, String rest) {
        if (fares == null) {
            return "";
        }
        int colon = rest.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String from = rest.substring(0, colon);
        String to = rest.substring(colon + 1);
        return fares.calculator().quote(from, to).map(quote -> fares.store().table().format(quote.fare())).orElse("");
    }

    private @Nullable String aboutTrain(Network network, Player player, String field) {
        Optional<TrainHandle> train = trains.trainOf(player);
        TrainView view = train.flatMap(handle -> runtime.view(handle.name())).orElse(null);
        if (field.equals("station")) {
            if (view != null && view.currentStationId().isPresent()) {
                return network.stationName(view.currentStationId().get());
            }
            return nearestStation(network, player.getLocation()).map(network::stationName).orElse("");
        }
        if (field.equals("train")) {
            return train.map(TrainHandle::name).orElse("");
        }
        if (view == null) {
            return switch (field) {
                case "line", "line_short", "service", "destination", "next", "eta", "status" -> "";
                default -> null;
            };
        }
        return switch (field) {
            case "line" -> view.line().name();
            case "line_short" -> view.line().shortName();
            case "service" -> view.service().name();
            case "destination" -> view.destinationName(network);
            case "next" -> view.nextStationId().map(network::stationName).orElse("");
            case "eta" -> view.etaNext().isPresent() ? plain(messages.get().duration(view.etaNext().getAsDouble())) : "";
            case "status" -> view.completed() ? "Not in service" : view.atTerminus() ? "Terminating" : view.atStop() ? "Boarding"
                    : view.held() ? "Held" : "Running";
            default -> null;
        };
    }

    private static Optional<String> nearestStation(Network network, Location at) {
        if (at.getWorld() == null) {
            return Optional.empty();
        }
        double best = (double) NEAR_BLOCKS * NEAR_BLOCKS;
        String found = null;
        for (Map.Entry<BlockPos, PlatformRef> trigger : network.triggers().entrySet()) {
            if (!trigger.getKey().world().equals(at.getWorld().getName())) {
                continue;
            }
            double distance = trigger.getKey().distanceSquared(at.getX(), at.getY(), at.getZ());
            if (distance < best) {
                best = distance;
                found = trigger.getValue().stationId();
            }
        }
        return Optional.ofNullable(found);
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
