package dev.advancedtrains.atcs.map;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Transfer;
import dev.advancedtrains.atcs.operation.TrackPaths;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Draws stations, lines and live trains on BlueMap. Only loaded when the BlueMap plugin is present.
 */
public final class BlueMapLayer implements MapLayer {
    private static final String NETWORK_SET = "atcs-network";
    private static final String TRAIN_SET = "atcs-trains";

    private final AtcsSettings.BlueMap settings;
    private final Logger logger;
    private final Consumer<BlueMapAPI> onEnable = this::enabled;
    private final Consumer<BlueMapAPI> onDisable = this::disabled;
    private BlueMapAPI api;
    private Map<String, MarkerSet> networkSets = Map.of();
    private Map<String, MarkerSet> trainSets = Map.of();

    private final Plugin plugin;

    public BlueMapLayer(Plugin plugin, AtcsSettings.BlueMap settings, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = logger;
        BlueMapAPI.onEnable(onEnable);
        BlueMapAPI.onDisable(onDisable);
    }

    @Override
    public String name() {
        return "BlueMap";
    }

    @Override
    public synchronized boolean ready() {
        return api != null;
    }

    private void enabled(BlueMapAPI enabledApi) {
        // BlueMap finishes loading on its own thread; Bukkit worlds may only be touched on the main thread.
        if (!Bukkit.isPrimaryThread()) {
            try {
                Bukkit.getScheduler().runTask(plugin, () -> enabled(enabledApi));
            } catch (RuntimeException exception) {
                // The plugin is disabling; nothing to attach to.
            }
            return;
        }
        synchronized (this) {
            api = enabledApi;
            attach(networkSets, NETWORK_SET);
            attach(trainSets, TRAIN_SET);
        }
    }

    private synchronized void disabled(BlueMapAPI disabledApi) {
        api = null;
    }

    @Override
    public synchronized void showNetwork(Network network, TrackPaths paths, StationLocator locator) {
        Map<String, MarkerSet> sets = new HashMap<>();
        if (settings.showLines()) {
            for (MapGeometry.Segment segment : MapGeometry.segments(network, paths, locator)) {
                if (segment.points().size() < 2) {
                    continue;
                }
                de.bluecolored.bluemap.api.math.Line.Builder line = de.bluecolored.bluemap.api.math.Line.builder();
                segment.points().forEach(point -> line.addPoint(new Vector3d(point.x(), point.y() + 0.5, point.z())));
                int rgb = segment.line().color();
                LineMarker marker = LineMarker.builder()
                        .label(segment.line().name())
                        .line(line.build())
                        .lineColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, segment.recorded() ? 1f : 0.55f))
                        .lineWidth(segment.recorded() ? settings.lineWidth() : Math.max(1, settings.lineWidth() - 2))
                        .depthTestEnabled(false)
                        .centerPosition()
                        .build();
                set(sets, segment.world()).put("line_" + segment.id(), marker);
            }
        }
        if (settings.showLines()) {
            // Walking transfers: a thin grey line between the two stations.
            for (Transfer transfer : network.transfers()) {
                var from = network.station(transfer.a()).flatMap(locator::station);
                var to = network.station(transfer.b()).flatMap(locator::station);
                if (from.isEmpty() || to.isEmpty() || !from.get().world().equals(to.get().world())) {
                    continue;
                }
                LineMarker marker = LineMarker.builder()
                        .label("Walk: " + network.stationName(transfer.a()) + " - " + network.stationName(transfer.b()))
                        .line(de.bluecolored.bluemap.api.math.Line.builder()
                                .addPoint(new Vector3d(from.get().x() + 0.5, from.get().y() + 1, from.get().z() + 0.5))
                                .addPoint(new Vector3d(to.get().x() + 0.5, to.get().y() + 1, to.get().z() + 0.5))
                                .build())
                        .lineColor(new Color(90, 90, 90, 0.9f))
                        .lineWidth(2)
                        .depthTestEnabled(false)
                        .centerPosition()
                        .build();
                set(sets, from.get().world()).put("walk_" + transfer.a() + "_" + transfer.b(), marker);
            }
        }
        if (settings.showStations()) {
            for (MapGeometry.StationPoint point : MapGeometry.stations(network, locator)) {
                POIMarker marker = POIMarker.builder()
                        .label(point.station().name())
                        .detail(stationDetail(point))
                        .position(point.position().x() + 0.5, point.position().y() + 1.5, point.position().z() + 0.5)
                        .build();
                set(sets, point.position().world()).put("station_" + point.station().id(), marker);
            }
        }
        detach(networkSets, NETWORK_SET);
        networkSets = sets;
        attach(networkSets, NETWORK_SET);
    }

    @Override
    public synchronized void showTrains(List<TrainMarker> trains) {
        if (!settings.showTrains()) {
            return;
        }
        Map<String, MarkerSet> sets = new HashMap<>(trainSets);
        Set<String> worldsWithTrains = new HashSet<>();
        Map<String, Set<String>> wanted = new HashMap<>();
        for (TrainMarker train : trains) {
            worldsWithTrains.add(train.world());
            MarkerSet set = sets.computeIfAbsent(train.world(), ignored -> newSet(settings.markerSetLabel() + " trains"));
            String id = "train_" + train.id();
            wanted.computeIfAbsent(train.world(), ignored -> new HashSet<>()).add(id);
            POIMarker marker = set.get(id) instanceof POIMarker existing ? existing : null;
            if (marker == null) {
                marker = POIMarker.builder().label(train.label()).position(train.x(), train.y() + 1, train.z()).build();
                set.put(id, marker);
            } else {
                marker.setLabel(train.label());
                marker.setPosition(train.x(), train.y() + 1, train.z());
            }
            marker.setDetail("<div style=\"padding:4px 2px\"><b style=\"color:" + hex(train.color()) + "\">"
                    + escape(train.label()) + "</b><br>" + escape(train.detail()) + "</div>");
        }
        for (Map.Entry<String, MarkerSet> entry : sets.entrySet()) {
            Set<String> keep = wanted.getOrDefault(entry.getKey(), Set.of());
            entry.getValue().getMarkers().keySet().removeIf(id -> !keep.contains(id));
        }
        Map<String, MarkerSet> added = new HashMap<>();
        for (String world : worldsWithTrains) {
            if (!trainSets.containsKey(world)) {
                added.put(world, sets.get(world));
            }
        }
        trainSets = sets;
        attach(added, TRAIN_SET);
    }

    @Override
    public synchronized void close() {
        detach(networkSets, NETWORK_SET);
        detach(trainSets, TRAIN_SET);
        networkSets = Map.of();
        trainSets = Map.of();
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
    }

    private MarkerSet set(Map<String, MarkerSet> sets, String world) {
        return sets.computeIfAbsent(world, ignored -> newSet(settings.markerSetLabel()));
    }

    private static MarkerSet newSet(String label) {
        return MarkerSet.builder().label(label).toggleable(true).defaultHidden(false).build();
    }

    private void attach(Map<String, MarkerSet> sets, String id) {
        forEachMap(sets, (map, set) -> map.getMarkerSets().put(id, set));
    }

    private void detach(Map<String, MarkerSet> sets, String id) {
        forEachMap(sets, (map, set) -> map.getMarkerSets().remove(id));
    }

    private void forEachMap(Map<String, MarkerSet> sets, java.util.function.BiConsumer<BlueMapMap, MarkerSet> action) {
        if (api == null) {
            return;
        }
        for (Map.Entry<String, MarkerSet> entry : sets.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            try {
                BlueMapWorld blueMapWorld = api.getWorld(world).orElse(null);
                if (blueMapWorld != null) {
                    for (BlueMapMap map : blueMapWorld.getMaps()) {
                        action.accept(map, entry.getValue());
                    }
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not update BlueMap markers for " + entry.getKey(), exception);
            }
        }
    }

    private static String stationDetail(MapGeometry.StationPoint point) {
        StringBuilder html = new StringBuilder("<div style=\"padding:4px 2px\"><b>")
                .append(escape(point.station().name())).append("</b>");
        for (Line line : point.lines()) {
            html.append("<br><span style=\"color:").append(hex(line.color())).append("\">&#9632;</span> ")
                    .append(escape(line.name()));
            if (line.suspended()) {
                html.append(" <i>(suspended)</i>");
            }
        }
        return html.append("</div>").toString();
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
