package dev.advancedtrains.atcs.map;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.display.Logos;
import dev.advancedtrains.atcs.network.Box;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Transfer;
import dev.advancedtrains.atcs.operation.TrackPaths;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Draws the railway on BlueMap: one marker set per line (so BlueMap's own menu toggles each), the
 * stations with their generated logos as icons (small stations fade out when zoomed far out),
 * walking transfers, and the trains as line badges. Station popups carry the next departures and
 * train popups the next stop; both are refreshed with the trains. Only loaded when the BlueMap
 * plugin is present.
 */
public final class BlueMapLayer implements MapLayer {
    private static final String STATIONS_SET = "atcs-stations";
    private static final String TRANSFERS_SET = "atcs-transfers";
    private static final String TRAINS_SET = "atcs-trains";
    private static final String LINE_SET_PREFIX = "atcs-line-";
    private static final int TRAIN_ICON = 28;
    private static final Color SUSPENDED = new Color(140, 140, 140, 0.75f);

    private final AtcsSettings.BlueMap settings;
    private final Logos logos;
    private final @Nullable String websiteUrl;
    private final Logger logger;
    private final Plugin plugin;
    private final Consumer<BlueMapAPI> onEnable = this::enabled;
    private final Consumer<BlueMapAPI> onDisable = this::disabled;
    private BlueMapAPI api;
    /** Marker sets by set id, then by world. */
    private Map<String, Map<String, MarkerSet>> networkSets = Map.of();
    private Map<String, MarkerSet> trainSets = Map.of();
    /** Station marker by station id, with the fixed part of its popup, for live refreshes. */
    private final Map<String, POIMarker> stationMarkers = new HashMap<>();
    private final Map<String, String> stationBase = new HashMap<>();
    /** Logo key to the image URL BlueMap serves it under. */
    private final Map<String, String> images = new HashMap<>();
    private final Map<String, String> lineIconByLine = new HashMap<>();
    /** The network was drawn before the API was up, so its icons are missing until it is drawn again. */
    private boolean drawnWithoutApi;

    public BlueMapLayer(Plugin plugin, AtcsSettings.BlueMap settings, Logos logos, @Nullable String websiteUrl, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.logos = logos;
        this.websiteUrl = websiteUrl;
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
            images.clear();
            lineIconByLine.clear();
            networkSets.forEach((id, sets) -> attach(sets, id));
            attach(trainSets, TRAINS_SET);
            drawnWithoutApi = !networkSets.isEmpty();
        }
    }

    private synchronized void disabled(BlueMapAPI disabledApi) {
        api = null;
    }

    @Override
    public synchronized boolean wantsNetwork() {
        return drawnWithoutApi && api != null;
    }

    @Override
    public synchronized void showNetwork(Network network, TrackPaths paths, StationLocator locator) {
        drawnWithoutApi = api == null;
        Map<String, Map<String, MarkerSet>> sets = new LinkedHashMap<>();
        if (settings.showLines()) {
            for (MapGeometry.Segment segment : MapGeometry.segments(network, paths, locator)) {
                if (segment.points().size() < 2) {
                    continue;
                }
                de.bluecolored.bluemap.api.math.Line.Builder line = de.bluecolored.bluemap.api.math.Line.builder();
                segment.points().forEach(point -> line.addPoint(new Vector3d(point.x(), point.y() + 0.5, point.z())));
                int rgb = segment.line().color();
                Color color = segment.line().suspended() ? SUSPENDED
                        : new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, segment.recorded() ? 1f : 0.55f);
                LineMarker marker = LineMarker.builder()
                        .label(segment.line().name() + (segment.line().suspended() ? " (suspended)" : ""))
                        .line(line.build())
                        .lineColor(color)
                        .lineWidth(segment.recorded() ? settings.lineWidth() : Math.max(1, settings.lineWidth() - 2))
                        .depthTestEnabled(false)
                        .centerPosition()
                        .build();
                set(sets, LINE_SET_PREFIX + segment.line().id(), segment.line().name(), segment.world()).put("line_" + segment.id(), marker);
            }
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
                set(sets, TRANSFERS_SET, "Walking transfers", from.get().world()).put("walk_" + transfer.a() + "_" + transfer.b(), marker);
            }
        }
        stationMarkers.clear();
        stationBase.clear();
        if (settings.showStations()) {
            Set<String> termini = termini(network);
            for (MapGeometry.StationPoint point : MapGeometry.stations(network, locator)) {
                POIMarker.Builder builder = POIMarker.builder()
                        .label(point.station().name())
                        .position(point.position().x() + 0.5, point.position().y() + 1.5, point.position().z() + 0.5);
                int size = settings.stationIconSize();
                if (settings.logos() && size > 0) {
                    String url = image(point.position().world(), logos.stationKey(network, point.station(), size),
                            () -> logos.station(network, point.station(), size));
                    if (url != null) {
                        builder.icon(url, size / 2, size / 2);
                    }
                }
                POIMarker marker = builder.build();
                boolean major = point.lines().size() >= 2 || termini.contains(point.station().id());
                if (!major && settings.minorStationMaxDistance() > 0) {
                    marker.setMaxDistance(settings.minorStationMaxDistance());
                }
                String base = stationHeader(network, point);
                stationBase.put(point.station().id(), base);
                marker.setDetail(base + "</div>");
                stationMarkers.put(point.station().id(), marker);
                set(sets, STATIONS_SET, "Stations", point.position().world()).put("station_" + point.station().id(), marker);
                Box footprint = point.station().footprint();
                if (footprint != null) {
                    int rgb = point.lines().isEmpty() ? 0x9E9E9E : point.lines().get(0).color();
                    ExtrudeMarker shape = ExtrudeMarker.builder()
                            .label(point.station().name())
                            .shape(Shape.createRect(footprint.minX(), footprint.minZ(), footprint.maxX() + 1, footprint.maxZ() + 1),
                                    footprint.minY(), footprint.maxY() + 1)
                            .centerPosition()
                            .fillColor(new Color(rgb, 0.18f))
                            .lineColor(new Color(rgb, 0.8f))
                            .lineWidth(2)
                            .depthTestEnabled(true)
                            .build();
                    shape.setDetail(base + "</div>");
                    set(sets, STATIONS_SET, "Stations", footprint.world()).put("footprint_" + point.station().id(), shape);
                }
            }
        }
        networkSets.forEach((id, old) -> detach(old, id));
        networkSets = sets;
        networkSets.forEach((id, fresh) -> attach(fresh, id));
    }

    /** Stations where a non-circular service starts or ends; always shown, however far out. */
    private static Set<String> termini(Network network) {
        Set<String> ids = new HashSet<>();
        for (Service service : network.services()) {
            if (!service.circular() && service.size() > 0) {
                ids.add(service.stop(0).stationId());
                ids.add(service.stop(service.size() - 1).stationId());
            }
        }
        return ids;
    }

    @Override
    public synchronized void showLive(List<TrainMarker> trains, List<StationLive> stations) {
        for (StationLive station : stations) {
            POIMarker marker = stationMarkers.get(station.stationId());
            String base = stationBase.get(station.stationId());
            if (marker == null || base == null) {
                continue;
            }
            marker.setDetail(base + departures(station) + "</div>");
        }
        if (!settings.showTrains()) {
            return;
        }
        Map<String, MarkerSet> sets = new HashMap<>(trainSets);
        Set<String> worldsWithTrains = new HashSet<>();
        Map<String, Set<String>> wanted = new HashMap<>();
        for (TrainMarker train : trains) {
            worldsWithTrains.add(train.world());
            MarkerSet set = sets.computeIfAbsent(train.world(), ignored -> newSet("Trains"));
            String id = "train_" + train.id();
            wanted.computeIfAbsent(train.world(), ignored -> new HashSet<>()).add(id);
            POIMarker marker = set.get(id) instanceof POIMarker existing ? existing : null;
            if (marker == null) {
                marker = POIMarker.builder().label(train.label()).position(train.x(), train.y() + 1, train.z()).build();
                String icon = trainIcon(train);
                if (icon != null) {
                    marker.setIcon(icon, TRAIN_ICON / 2, TRAIN_ICON / 2);
                }
                set.put(id, marker);
            } else {
                marker.setLabel(train.label());
                marker.setPosition(train.x(), train.y() + 1, train.z());
            }
            marker.setDetail("<div style=\"padding:4px 2px;min-width:160px\"><b style=\"color:" + hex(train.color()) + "\">"
                    + escape(train.label()) + "</b><br>" + escape(train.detail())
                    + (train.held() ? "<br><i>Held</i>" : "") + "</div>");
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
        attach(added, TRAINS_SET);
    }

    private @Nullable String trainIcon(TrainMarker train) {
        if (!settings.trainIcons() || !settings.logos()) {
            return null;
        }
        return lineIconByLine.computeIfAbsent(train.world() + "|" + train.lineId(), key -> {
            Line line = Line.of(train.lineId(), train.lineId(), train.color()).withShortName(train.label().split(" ", 2)[0]);
            return image(train.world(), logos.lineKey(line, TRAIN_ICON), () -> logos.line(line, TRAIN_ICON));
        });
    }

    /**
     * The URL BlueMap serves an image under, written into the asset storage of the world's first
     * map on first use. Asset URLs are served from the web root, so every map of the world can
     * show the marker.
     */
    private @Nullable String image(String world, String key, java.util.function.Supplier<BufferedImage> render) {
        if (api == null) {
            return null;
        }
        String cacheKey = world + "|" + key;
        if (images.containsKey(cacheKey)) {
            return images.get(cacheKey);
        }
        String url = null;
        try {
            World bukkitWorld = Bukkit.getWorld(world);
            BlueMapWorld blueMapWorld = bukkitWorld == null ? null : api.getWorld(bukkitWorld).orElse(null);
            BlueMapMap map = blueMapWorld == null ? null : blueMapWorld.getMaps().stream().findFirst().orElse(null);
            if (map != null) {
                String name = "atcs/" + key.replaceAll("[^a-z0-9-]+", "-") + ".png";
                try (java.io.OutputStream out = map.getAssetStorage().writeAsset(name)) {
                    javax.imageio.ImageIO.write(render.get(), "png", out);
                }
                url = map.getAssetStorage().getAssetUrl(name);
            }
        } catch (IOException | RuntimeException | LinkageError exception) {
            logger.log(Level.WARNING, "Could not add a logo to BlueMap; the default marker is used", exception);
        }
        images.put(cacheKey, url);
        return url;
    }

    @Override
    public synchronized void close() {
        networkSets.forEach((id, sets) -> detach(sets, id));
        detach(trainSets, TRAINS_SET);
        networkSets = Map.of();
        trainSets = Map.of();
        stationMarkers.clear();
        stationBase.clear();
        BlueMapAPI.unregisterListener(onEnable);
        BlueMapAPI.unregisterListener(onDisable);
    }

    private static MarkerSet set(Map<String, Map<String, MarkerSet>> sets, String id, String label, String world) {
        return sets.computeIfAbsent(id, ignored -> new HashMap<>()).computeIfAbsent(world, ignored -> newSet(label));
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

    private void forEachMap(Map<String, MarkerSet> sets, BiConsumer<BlueMapMap, MarkerSet> action) {
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

    /** The fixed part of a station popup: name, lines, link; left open for the departures. */
    private String stationHeader(Network network, MapGeometry.StationPoint point) {
        StringBuilder html = new StringBuilder("<div style=\"padding:4px 2px;min-width:180px\"><b>")
                .append(escape(point.station().name())).append("</b>");
        for (Line line : point.lines()) {
            html.append("<br><span style=\"color:").append(hex(line.color())).append("\">&#9632;</span> ")
                    .append(escape(line.name()));
            if (line.suspended()) {
                html.append(" <i>(suspended)</i>");
            }
        }
        for (Transfer transfer : network.transfersAt(point.station().id())) {
            html.append("<br><small>Walk to ").append(escape(network.stationName(transfer.other(point.station().id())))).append("</small>");
        }
        if (websiteUrl != null) {
            html.append("<br><a href=\"").append(escape(websiteUrl)).append("#station=").append(escape(point.station().id()))
                    .append("\" target=\"_blank\" rel=\"noopener\">Departures and planner</a>");
        }
        return html.toString();
    }

    private static String departures(StationLive station) {
        if (station.departures().isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<hr style=\"border:0;border-top:1px solid #ccc;margin:4px 0\">");
        for (DepartureLine departure : station.departures()) {
            html.append("<div><b style=\"color:").append(hex(departure.lineColor())).append("\">").append(escape(departure.lineBadge()))
                    .append("</b> ").append(escape(departure.destination()));
            if (departure.platform() != null) {
                html.append(" <small>pl. ").append(escape(departure.platform())).append("</small>");
            }
            html.append(" <span style=\"float:right;margin-left:8px\">").append(escape(departure.time())).append("</span></div>");
        }
        return html.toString();
    }

    private static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
