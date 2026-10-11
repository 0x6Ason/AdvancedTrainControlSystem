package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.PlatformRef;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Stop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Builds a service's stop list by riding (or following) a train: every platform it enters is
 * appended until the operator stops the recording.
 */
public final class ServiceRecorder {

    public record Session(UUID player, String trainName, ServiceKey service, List<Stop> stops) {
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Supplier<Network> network;

    public ServiceRecorder(Supplier<Network> network) {
        this.network = network;
    }

    public void start(Player player, String trainName, ServiceKey service) {
        sessions.put(player.getUniqueId(), new Session(player.getUniqueId(), trainName, service, new ArrayList<>()));
    }

    public Optional<Session> session(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    public Optional<Session> finish(Player player) {
        return Optional.ofNullable(sessions.remove(player.getUniqueId()));
    }

    /** Follows a train that was renamed. */
    public void rename(String from, String to) {
        sessions.replaceAll((player, session) -> session.trainName().equalsIgnoreCase(from)
                ? new Session(session.player(), to, session.service(), session.stops())
                : session);
    }

    public void onPlatform(String trainName, PlatformRef platform, boolean entering) {
        if (!entering) {
            return;
        }
        for (Session session : sessions.values()) {
            if (!session.trainName().equalsIgnoreCase(trainName)) {
                continue;
            }
            List<Stop> stops = session.stops();
            if (!stops.isEmpty() && stops.get(stops.size() - 1).stationId().equals(platform.stationId())) {
                continue;
            }
            stops.add(new Stop(platform.stationId(), platform.platformId(), false));
            notify(session, Component.text("Recorded stop " + stops.size() + ": ", NamedTextColor.GRAY)
                    .append(Component.text(network.get().stationName(platform.stationId()), NamedTextColor.WHITE))
                    .append(Component.text(" (platform " + platform.platformId() + ")", NamedTextColor.DARK_GRAY)));
        }
    }

    public void trainRemoved(String trainName) {
        for (Session session : sessions.values()) {
            if (session.trainName().equalsIgnoreCase(trainName)) {
                notify(session, Component.text("The recorded train is gone. Run /atcs service record stop to save the "
                        + session.stops().size() + " recorded stops, or cancel.", NamedTextColor.GOLD));
            }
        }
    }

    private static void notify(Session session, Component message) {
        Player player = Bukkit.getPlayer(session.player());
        if (player != null) {
            player.sendMessage(Component.text("ATCS ", NamedTextColor.GOLD).append(message));
        }
    }
}
