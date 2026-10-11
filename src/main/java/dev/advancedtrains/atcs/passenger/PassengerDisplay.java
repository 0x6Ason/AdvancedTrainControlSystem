package dev.advancedtrains.atcs.passenger;

import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.operation.JourneyEvent;
import dev.advancedtrains.atcs.operation.TrainView;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows passenger information to the riders of a train: a boss bar in the line colour, arrival
 * titles, chat announcements, action-bar notes, and chimes. Main thread only.
 */
public final class PassengerDisplay {
    public static final String PERMISSION = "atcs.passenger";

    private static final int[][] BAR_COLORS = {
            {0xE94FAF, BossBar.Color.PINK.ordinal()},
            {0x2A9DF4, BossBar.Color.BLUE.ordinal()},
            {0xE33B3B, BossBar.Color.RED.ordinal()},
            {0x3FC63F, BossBar.Color.GREEN.ordinal()},
            {0xE8D53A, BossBar.Color.YELLOW.ordinal()},
            {0x9B4FD8, BossBar.Color.PURPLE.ordinal()},
            {0xE8E8E8, BossBar.Color.WHITE.ordinal()},
    };

    private final Map<String, TrainBar> bars = new HashMap<>();
    private AtcsSettings.Passenger settings;
    private Messages messages;

    public PassengerDisplay(AtcsSettings.Passenger settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    public void configure(AtcsSettings.Passenger newSettings, Messages newMessages) {
        this.settings = newSettings;
        this.messages = newMessages;
        clear();
    }

    public void announce(JourneyEvent event, @Nullable TrainView view, Collection<? extends Player> riders, Network network) {
        if (!settings.enabled()) {
            return;
        }
        List<Player> audience = audience(riders);
        if (audience.isEmpty()) {
            return;
        }
        switch (event) {
            case JourneyEvent.Arrived arrived when view != null -> {
                TagResolver placeholders = Placeholders.train(view, network, messages);
                if (settings.arrivalTitle()) {
                    Component subtitle = messages.render(arrived.terminus()
                            ? "passenger.terminus-subtitle" : "passenger.arrival-subtitle", placeholders);
                    showTitle(audience, messages.render("passenger.arrival-title", placeholders), subtitle);
                }
                play(audience, settings.arrivalSound());
                if (settings.chatAnnouncements()) {
                    if (arrived.terminus()) {
                        send(audience, messages.render("passenger.terminus-chat", placeholders));
                    }
                    Placeholders.interchanges(network, arrived.platform().stationId(), view.line(), messages)
                            .ifPresent(lines -> send(audience, messages.render("passenger.interchange-here",
                                    placeholders, Placeholder.component("interchanges", lines))));
                }
            }
            case JourneyEvent.Departed departed when view != null && departed.nextIndex() >= 0 -> {
                play(audience, settings.departureSound());
                if (settings.chatAnnouncements()) {
                    TagResolver placeholders = Placeholders.train(view, network, messages);
                    send(audience, messages.render(view.nextIsTerminus()
                            ? "passenger.next-station-terminus" : "passenger.next-station", placeholders));
                    view.nextStationId()
                            .flatMap(next -> Placeholders.interchanges(network, next, view.line(), messages))
                            .ifPresent(lines -> send(audience, messages.render("passenger.interchange-next",
                                    placeholders, Placeholder.component("interchanges", lines))));
                }
            }
            case JourneyEvent.Approaching ignored when view != null -> {
                actionBar(audience, messages.render("passenger.approaching", Placeholders.train(view, network, messages)));
                play(audience, settings.approachingSound());
            }
            case JourneyEvent.Passed passed when settings.showPassing() -> actionBar(audience, messages.render(
                    "passenger.passing", Placeholders.station(network, passed.platform().stationId(), null)));
            case JourneyEvent.UnassignedArrived arrived
                    when settings.unassignedTrains() == AtcsSettings.UnassignedTrains.TITLE -> {
                TagResolver placeholders = Placeholders.station(network,
                        arrived.platform().stationId(), arrived.platform().platformId());
                showTitle(audience, messages.render("passenger.unassigned-arrival-title", placeholders),
                        messages.render("passenger.unassigned-arrival-subtitle", placeholders));
                play(audience, settings.arrivalSound());
            }
            case JourneyEvent.ServiceChanged changed when changed.to() != null && view != null && !view.completed() ->
                    send(audience, messages.render("passenger.boarding", Placeholders.train(view, network, messages)));
            default -> {
            }
        }
    }

    /** Greets a rider who just boarded a train in service. */
    public void boarded(Player player, @Nullable TrainView view, Network network) {
        if (!settings.enabled() || view == null || view.completed() || !player.hasPermission(PERMISSION)) {
            return;
        }
        player.sendMessage(messages.render("passenger.boarding", Placeholders.train(view, network, messages)));
    }

    /** Updates the train's boss bar and who sees it. */
    public void refresh(String trainId, @Nullable TrainView view, Collection<? extends Player> riders, Network network) {
        if (!settings.enabled() || !settings.bossBar() || view == null) {
            remove(trainId);
            return;
        }
        List<Player> audience = audience(riders);
        if (audience.isEmpty()) {
            remove(trainId);
            return;
        }
        TrainBar bar = bars.computeIfAbsent(trainId, ignored -> new TrainBar());
        bar.bar.name(barText(view, network));
        bar.bar.progress(view.progress());
        bar.bar.color(nearestColor(view.line().color()));
        Set<UUID> present = new HashSet<>();
        for (Player player : audience) {
            present.add(player.getUniqueId());
            if (bar.viewers.put(player.getUniqueId(), player) == null) {
                player.showBossBar(bar.bar);
            }
        }
        for (Iterator<Map.Entry<UUID, Player>> iterator = bar.viewers.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<UUID, Player> entry = iterator.next();
            if (!present.contains(entry.getKey())) {
                entry.getValue().hideBossBar(bar.bar);
                iterator.remove();
            }
        }
    }

    private Component barText(TrainView view, Network network) {
        TagResolver placeholders = Placeholders.train(view, network, messages);
        String key;
        if (view.line().suspended()) {
            key = "passenger.boss-bar.suspended";
        } else if (view.completed() || (view.nextIndex() < 0 && !view.atStop())) {
            key = "passenger.boss-bar.not-in-service";
        } else if (view.atTerminus()) {
            key = "passenger.boss-bar.terminating";
        } else if (view.atStop()) {
            key = "passenger.boss-bar.at-station";
        } else {
            key = "passenger.boss-bar.running";
        }
        return messages.render(key, placeholders);
    }

    /** Follows a train that was renamed. */
    public void rename(String from, String to) {
        TrainBar bar = bars.remove(from);
        if (bar != null) {
            bars.put(to, bar);
        }
    }

    public void remove(String trainId) {
        TrainBar bar = bars.remove(trainId);
        if (bar != null) {
            bar.viewers.values().forEach(player -> player.hideBossBar(bar.bar));
        }
    }

    public void removePlayer(Player player) {
        for (TrainBar bar : bars.values()) {
            if (bar.viewers.remove(player.getUniqueId()) != null) {
                player.hideBossBar(bar.bar);
            }
        }
    }

    public void clear() {
        for (String trainId : List.copyOf(bars.keySet())) {
            remove(trainId);
        }
    }

    public Set<String> trainsWithBars() {
        return Set.copyOf(bars.keySet());
    }

    private static List<Player> audience(Collection<? extends Player> riders) {
        return riders.stream()
                .filter(Player::isOnline)
                .filter(player -> player.hasPermission(PERMISSION))
                .map(player -> (Player) player)
                .toList();
    }

    private void showTitle(List<Player> audience, Component title, Component subtitle) {
        Title shown = Title.title(title, subtitle, Title.Times.times(
                ticks(settings.titleFadeIn()), ticks(settings.titleStay()), ticks(settings.titleFadeOut())));
        audience.forEach(player -> player.showTitle(shown));
    }

    private static void send(List<Player> audience, Component message) {
        if (message.equals(Component.empty())) {
            return;
        }
        audience.forEach(player -> player.sendMessage(message));
    }

    private static void actionBar(List<Player> audience, Component message) {
        if (message.equals(Component.empty())) {
            return;
        }
        audience.forEach(player -> player.sendActionBar(message));
    }

    private static void play(List<Player> audience, @Nullable Sound sound) {
        if (sound != null) {
            audience.forEach(player -> player.playSound(sound, Sound.Emitter.self()));
        }
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    static BossBar.Color nearestColor(int rgb) {
        int best = BossBar.Color.WHITE.ordinal();
        long bestDistance = Long.MAX_VALUE;
        for (int[] candidate : BAR_COLORS) {
            long distance = colorDistance(rgb, candidate[0]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate[1];
            }
        }
        return BossBar.Color.values()[best];
    }

    private static long colorDistance(int a, int b) {
        long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        long db = (a & 0xFF) - (b & 0xFF);
        return dr * dr * 3 + dg * dg * 4 + db * db * 2;
    }

    private static final class TrainBar {
        private final BossBar bar = BossBar.bossBar(Component.empty(), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        private final Map<UUID, Player> viewers = new HashMap<>();
    }
}
