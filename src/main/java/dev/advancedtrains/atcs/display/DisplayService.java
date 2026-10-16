package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.board.BoardRenderer;
import dev.advancedtrains.atcs.config.AtcsSettings;
import dev.advancedtrains.atcs.config.Messages;
import dev.advancedtrains.atcs.map.MapGeometry;
import dev.advancedtrains.atcs.map.StationLocator;
import dev.advancedtrains.atcs.network.BlockPos;
import dev.advancedtrains.atcs.network.Box;
import dev.advancedtrains.atcs.network.Display;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.operation.Departures;
import dev.advancedtrains.atcs.operation.TrackPaths;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Puts displays into the world and keeps them current: map items in item frames drawn by
 * {@link DisplayRenderer}, and hologram boards on text display entities. Only frames and
 * positions an operator selected are ever touched, and everything is recorded in network.yml
 * so it survives restarts and can be taken down again.
 */
public final class DisplayService {
    /** The id of the hand-held network map display. */
    public static final String HANDHELD_ID = "map";

    private final Logger logger;
    private final Logos logos;
    private final NamespacedKey hologramKey;
    private AtcsSettings.Displays settings;
    /** Our renderer on each map we draw, by map id. */
    private final Map<Integer, TileRenderer> renderers = new HashMap<>();
    private final Set<Integer> missingMaps = new HashSet<>();
    private final Map<String, String> liveSignatures = new HashMap<>();
    private final Map<String, UUID> hologramEntities = new HashMap<>();
    private boolean staticDirty = true;
    private int drawnPathsVersion = -1;

    /** Something about the selection or the world makes a display impossible. */
    public static final class DisplayException extends Exception {
        public DisplayException(String message) {
            super(message);
        }
    }

    /** Departures for a display, soonest first. */
    @FunctionalInterface
    public interface DepartureSource {
        List<Departures.Departure> departures(String stationId, Predicate<String> platformFilter, int count);
    }

    public DisplayService(Plugin plugin, AtcsSettings.Displays settings, Logos logos) {
        this.logger = plugin.getLogger();
        this.logos = logos;
        this.settings = settings;
        this.hologramKey = new NamespacedKey(plugin, "hologram");
    }

    public void reconfigure(AtcsSettings.Displays newSettings) {
        settings = newSettings;
        redrawAll();
    }

    /** The network changed: static pictures are drawn again on the next tick. */
    public void networkChanged() {
        staticDirty = true;
        liveSignatures.clear();
    }

    public void redrawAll() {
        networkChanged();
    }

    // ---------------------------------------------------------------- refresh

    /** Draws what changed. Called on the main thread as often as boards are refreshed. */
    public void tick(Network network, Messages messages, TrackPaths paths, StationLocator locator, DepartureSource departures) {
        if (network.displays().isEmpty()) {
            return;
        }
        boolean redrawStatic = staticDirty || drawnPathsVersion != paths.version();
        for (Display display : network.displays().values()) {
            try {
                if (display.kind() == Display.Kind.HOLOGRAM) {
                    refreshHologram(display, network, messages, departures);
                    continue;
                }
                attach(display);
                if (display.kind().live()) {
                    refreshBoardPicture(display, network, messages, departures);
                } else if (redrawStatic) {
                    drawStatic(display, network, paths, locator);
                }
            } catch (RuntimeException exception) {
                logger.log(Level.WARNING, "Could not refresh display " + display.id(), exception);
            }
        }
        if (redrawStatic) {
            staticDirty = false;
            drawnPathsVersion = paths.version();
        }
    }

    /** Makes sure every map of the display has our renderer, also after a restart. */
    private void attach(Display display) {
        for (int id : display.allMaps()) {
            if (renderers.containsKey(id)) {
                continue;
            }
            MapView view = Bukkit.getMap(id);
            if (view == null) {
                if (missingMaps.add(id)) {
                    logger.warning("Display " + display.id() + ": map " + id + " no longer exists; remove the display and make it again");
                }
                continue;
            }
            TileRenderer renderer = new TileRenderer(display.frames().isEmpty());
            prepare(view, renderer);
            renderers.put(id, renderer);
            staticDirty = true;
            liveSignatures.remove(display.id());
        }
    }

    private void prepare(MapView view, TileRenderer renderer) {
        for (MapRenderer existing : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(existing);
        }
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(renderer);
    }

    private void drawStatic(Display display, Network network, TrackPaths paths, StationLocator locator) {
        int width = Math.max(1, display.columns()) * DisplayRenderer.TILE;
        int height = Math.max(1, display.rows()) * DisplayRenderer.TILE;
        BufferedImage mosaic;
        DisplayRenderer.@Nullable Projection projection = null;
        String world = null;
        switch (display.kind()) {
            case ROADMAP -> {
                Service service = network.service(display.service()).orElse(null);
                if (service == null) {
                    return;
                }
                mosaic = DisplayRenderer.roadmap(network, service, display.stationId(), width, height);
            }
            case LOGO -> {
                Station station = network.station(display.stationId()).orElse(null);
                if (station == null) {
                    return;
                }
                int size = Math.min(width, height);
                mosaic = DisplayRenderer.logo(logos.station(network, station, size), station.name(), width, height);
            }
            case STATUS -> {
                List<DisplayRenderer.StatusRow> rows = new ArrayList<>();
                for (Line line : network.lines().values()) {
                    rows.add(new DisplayRenderer.StatusRow(line.shortName(), line.color(), line.name(), line.suspension()));
                }
                mosaic = DisplayRenderer.status("Service status", rows, width, height);
            }
            case MAP -> {
                world = mapWorld(display, network, locator);
                if (world == null) {
                    return;
                }
                DisplayRenderer.NetworkMap map = networkMap(network, paths, locator, world, display.stationId(), width, height);
                mosaic = map.image();
                projection = map.projection();
            }
            default -> {
                return;
            }
        }
        show(display, mosaic, projection, world);
    }

    private void show(Display display, BufferedImage mosaic, DisplayRenderer.@Nullable Projection projection, @Nullable String world) {
        for (Display.Frame frame : display.frames()) {
            TileRenderer renderer = renderers.get(frame.map());
            if (renderer != null) {
                renderer.show(DisplayRenderer.tile(mosaic, frame.column(), frame.row()), null, null);
            }
        }
        for (int id : display.maps()) {
            TileRenderer renderer = renderers.get(id);
            if (renderer != null) {
                renderer.show(DisplayRenderer.tile(mosaic, 0, 0), projection, world);
            }
        }
    }

    /** The world a network map shows: where its frames hang, or else where most stations are. */
    private static @Nullable String mapWorld(Display display, Network network, StationLocator locator) {
        if (!display.frames().isEmpty()) {
            return display.frames().get(0).at().world();
        }
        if (!display.maps().isEmpty()) {
            MapView view = Bukkit.getMap(display.maps().get(0));
            if (view != null && view.getWorld() != null) {
                return view.getWorld().getName();
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        for (MapGeometry.StationPoint point : MapGeometry.stations(network, locator)) {
            counts.merge(point.position().world(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static DisplayRenderer.NetworkMap networkMap(Network network, TrackPaths paths, StationLocator locator, String world,
                                                         @Nullable String hereId, int width, int height) {
        List<DisplayRenderer.MapLine> lines = new ArrayList<>();
        for (MapGeometry.Segment segment : MapGeometry.spread(MapGeometry.segments(network, paths, locator))) {
            if (!segment.world().equals(world)) {
                continue;
            }
            List<double[]> points = new ArrayList<>();
            for (TrackPaths.Point point : segment.points()) {
                points.add(new double[] {point.x(), point.z()});
            }
            lines.add(new DisplayRenderer.MapLine(segment.line().suspended() ? 0x9E9E9E : segment.line().color(), points));
        }
        Set<String> termini = new HashSet<>();
        for (Service service : network.services()) {
            if (!service.circular() && service.size() > 0) {
                termini.add(service.stop(0).stationId());
                termini.add(service.stop(service.size() - 1).stationId());
            }
        }
        List<DisplayRenderer.MapStation> stations = new ArrayList<>();
        for (MapGeometry.StationPoint point : MapGeometry.stations(network, locator)) {
            if (!point.position().world().equals(world)) {
                continue;
            }
            stations.add(new DisplayRenderer.MapStation(point.station().id(), point.station().name(),
                    point.position().x() + 0.5, point.position().z() + 0.5,
                    point.lines().stream().map(Line::color).toList(), termini.contains(point.station().id())));
        }
        return DisplayRenderer.networkMap("", lines, stations, hereId, width, height);
    }

    private void refreshBoardPicture(Display display, Network network, Messages messages, DepartureSource departures) {
        Station station = network.station(display.stationId()).orElse(null);
        if (station == null) {
            return;
        }
        int rows = Math.max(1, display.rows()) * 5;
        List<Departures.Departure> upcoming = departures.departures(station.id(), display::includesPlatform, rows);
        List<DisplayRenderer.BoardRow> picture = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (Departures.Departure departure : upcoming) {
            Line line = network.line(departure.service().key().lineId()).orElse(null);
            if (line == null) {
                continue;
            }
            String destination = departure.service().destinationFrom(departure.stopIndex())
                    .map(stop -> network.stationName(stop.stationId())).orElse(departure.service().name());
            String status = PlainTextComponentSerializer.plainText().serialize(BoardRenderer.status(departure, line, messages));
            boolean good = departure.status() == Departures.Status.BOARDING
                    || departure.etaSeconds().isPresent() && departure.etaSeconds().getAsDouble() <= BoardRenderer.ARRIVING_SECONDS;
            String platform = display.platforms().size() == 1 ? null : BoardRenderer.platformName(network, departure);
            picture.add(new DisplayRenderer.BoardRow(line.shortName(), line.color(), destination, status, good && !line.suspended(), platform));
            signature.append(line.id()).append('|').append(destination).append('|').append(status).append('|').append(platform).append('\n');
        }
        String key = signature.toString();
        if (key.equals(liveSignatures.get(display.id()))) {
            return;
        }
        liveSignatures.put(display.id(), key);
        String subtitle = display.platforms().isEmpty() ? null : "Platform " + String.join(", ", display.platforms());
        String empty = PlainTextComponentSerializer.plainText().serialize(messages.render("hologram.empty"));
        BufferedImage mosaic = DisplayRenderer.board(station.name(), subtitle, picture, empty,
                Math.max(1, display.columns()) * DisplayRenderer.TILE, Math.max(1, display.rows()) * DisplayRenderer.TILE);
        show(display, mosaic, null, null);
    }

    private void refreshHologram(Display display, Network network, Messages messages, DepartureSource departures) {
        Display.Hologram hologram = display.hologram();
        Station station = network.station(display.stationId()).orElse(null);
        if (hologram == null || station == null) {
            return;
        }
        List<Component> lines = new ArrayList<>();
        String platform = display.platforms().isEmpty() ? "" : String.join(", ", display.platforms());
        lines.add(messages.render("hologram.header",
                Placeholder.unparsed("station", station.name()),
                Placeholder.component("platform_suffix", platform.isEmpty() ? Component.empty()
                        : messages.render("hologram.platform-suffix", Placeholder.unparsed("platform", platform)))));
        List<Departures.Departure> upcoming = departures.departures(station.id(), display::includesPlatform, settings.hologramDepartures());
        if (upcoming.isEmpty()) {
            lines.add(messages.render("hologram.empty"));
        }
        for (Departures.Departure departure : upcoming) {
            if (network.line(departure.service().key().lineId()).isEmpty()) {
                continue;
            }
            lines.add(messages.render("hologram.departure", BoardRenderer.placeholders(departure, network, messages)));
        }
        Component text = Component.join(JoinConfiguration.newlines(), lines);
        String signature = PlainTextComponentSerializer.plainText().serialize(text) + text.hashCode();
        TextDisplay entity = hologramEntity(display, hologram, true);
        if (entity == null) {
            return;
        }
        if (signature.equals(liveSignatures.get(display.id()))) {
            return;
        }
        liveSignatures.put(display.id(), signature);
        entity.text(text);
    }

    /** The hologram's entity, found by its tag or spawned again when it is gone and the chunk is loaded. */
    private @Nullable TextDisplay hologramEntity(Display display, Display.Hologram hologram, boolean spawnIfMissing) {
        UUID cached = hologramEntities.get(display.id());
        if (cached != null && Bukkit.getEntity(cached) instanceof TextDisplay entity && entity.isValid()) {
            return entity;
        }
        if (hologram.entity() != null && Bukkit.getEntity(hologram.entity()) instanceof TextDisplay entity && entity.isValid()) {
            hologramEntities.put(display.id(), entity.getUniqueId());
            return entity;
        }
        World world = Bukkit.getWorld(hologram.world());
        if (world == null || !world.isChunkLoaded(((int) Math.floor(hologram.x())) >> 4, ((int) Math.floor(hologram.z())) >> 4)) {
            return null;
        }
        BoundingBox around = new BoundingBox(hologram.x() - 1.5, hologram.y() - 1.5, hologram.z() - 1.5,
                hologram.x() + 1.5, hologram.y() + 3, hologram.z() + 1.5);
        for (Entity entity : world.getNearbyEntities(around, candidate -> candidate instanceof TextDisplay)) {
            String tag = entity.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
            if (display.id().equals(tag)) {
                hologramEntities.put(display.id(), entity.getUniqueId());
                return (TextDisplay) entity;
            }
        }
        if (!spawnIfMissing) {
            return null;
        }
        TextDisplay spawned = spawnHologram(display.id(), new Location(world, hologram.x(), hologram.y(), hologram.z()));
        hologramEntities.put(display.id(), spawned.getUniqueId());
        liveSignatures.remove(display.id());
        return spawned;
    }

    private TextDisplay spawnHologram(String id, Location at) {
        float scale = (float) settings.hologramScale();
        return at.getWorld().spawn(at, TextDisplay.class, entity -> {
            entity.setBillboard(org.bukkit.entity.Display.Billboard.VERTICAL);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(400);
            entity.setSeeThrough(settings.hologramSeeThrough());
            entity.setShadowed(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(org.bukkit.Color.fromARGB(200, 20, 24, 30));
            entity.setPersistent(true);
            entity.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            entity.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, id);
            entity.text(Component.text("ATCS"));
        });
    }

    // ---------------------------------------------------------------- creating

    /** An item frame and its place in the mosaic. */
    public record FrameSlot(ItemFrame frame, int column, int row) {
    }

    /** The item frames of a selection, arranged as the viewer sees them. */
    public record FrameGrid(World world, BlockFace facing, int columns, int rows, List<FrameSlot> slots) {
    }

    /** Finds the item frames in the selection and arranges them into a mosaic. */
    public FrameGrid frames(World world, Box selection, Network network) throws DisplayException {
        if (!world.getName().equals(selection.world())) {
            throw new DisplayException("The selection is in another world");
        }
        BoundingBox bounds = new BoundingBox(selection.minX(), selection.minY(), selection.minZ(),
                selection.maxX() + 1, selection.maxY() + 1, selection.maxZ() + 1);
        List<ItemFrame> frames = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(bounds, candidate -> candidate instanceof ItemFrame)) {
            frames.add((ItemFrame) entity);
        }
        if (frames.isEmpty()) {
            throw new DisplayException("No item frames in the selection (" + selection.describe()
                    + "). Hang item frames on a wall, select them with the wand, then run the command again.");
        }
        BlockFace facing = frames.get(0).getFacing();
        for (ItemFrame frame : frames) {
            if (frame.getFacing() != facing) {
                throw new DisplayException("All frames must hang on the same wall, facing the same way");
            }
        }
        if (facing == BlockFace.UP || facing == BlockFace.DOWN) {
            throw new DisplayException("Frames must hang on a wall, not on a floor or ceiling");
        }
        for (ItemFrame frame : frames) {
            BlockPos at = position(frame);
            for (Display other : network.displays().values()) {
                for (Display.Frame used : other.frames()) {
                    if (used.at().equals(at)) {
                        throw new DisplayException("The frame at " + at.coordinates() + " already belongs to display "
                                + other.id() + "; remove that first");
                    }
                }
            }
        }
        TreeSet<Integer> across = new TreeSet<>();
        TreeSet<Integer> down = new TreeSet<>();
        for (ItemFrame frame : frames) {
            across.add(across(frame, facing));
            down.add(-frame.getLocation().getBlockY());
        }
        List<Integer> columns = new ArrayList<>(across);
        List<Integer> rows = new ArrayList<>(down);
        List<FrameSlot> slots = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (ItemFrame frame : frames) {
            int column = columns.indexOf(across(frame, facing));
            int row = rows.indexOf(-frame.getLocation().getBlockY());
            if (!taken.add(column + "," + row)) {
                throw new DisplayException("Two frames share the same spot in the mosaic; is one of them in front of another?");
            }
            slots.add(new FrameSlot(frame, column, row));
        }
        if (slots.size() != columns.size() * rows.size()) {
            throw new DisplayException("Frames must fill a rectangle: found " + slots.size() + " frames in "
                    + columns.size() + " x " + rows.size());
        }
        slots.sort(Comparator.comparingInt(FrameSlot::row).thenComparingInt(FrameSlot::column));
        return new FrameGrid(world, facing, columns.size(), rows.size(), slots);
    }

    /** The frame's position along the wall, increasing to the viewer's right. */
    private static int across(ItemFrame frame, BlockFace facing) {
        Location at = frame.getLocation();
        return switch (facing) {
            case NORTH -> -at.getBlockX();
            case SOUTH -> at.getBlockX();
            case EAST -> -at.getBlockZ();
            case WEST -> at.getBlockZ();
            default -> at.getBlockX();
        };
    }

    private static BlockPos position(ItemFrame frame) {
        Location at = frame.getLocation();
        return new BlockPos(at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /** Creates the maps, puts them into the frames and returns the display to record. */
    public Display createFrames(String id, Display.Kind kind, @Nullable String stationId, List<String> platforms,
                                @Nullable ServiceKey service, FrameGrid grid, String label) {
        List<Display.Frame> frames = new ArrayList<>();
        for (FrameSlot slot : grid.slots()) {
            MapView view = newView(grid.world(), false);
            ItemFrame frame = slot.frame();
            frame.setItem(mapItem(view, label), false);
            frame.setRotation(Rotation.NONE);
            if (settings.fixFrames()) {
                frame.setFixed(true);
            }
            frames.add(new Display.Frame(position(frame), slot.column(), slot.row(), view.getId()));
        }
        staticDirty = true;
        return new Display(id, kind, stationId, platforms, service, grid.columns(), grid.rows(), frames, List.of(), null);
    }

    /** Spawns the hologram and returns the display to record. */
    public Display createHologram(String id, String stationId, List<String> platforms, Location at) {
        TextDisplay entity = spawnHologram(id, at);
        hologramEntities.put(id, entity.getUniqueId());
        return new Display(id, Display.Kind.HOLOGRAM, stationId, platforms, null, 0, 0, List.of(), List.of(),
                new Display.Hologram(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), entity.getUniqueId()));
    }

    /**
     * The hand-held network map: one shared map every copy shows, with each holder's own position
     * on it. Returns the existing display when its map still exists, otherwise a new one to record.
     */
    public Display handheld(Network network, World world) {
        Optional<Display> existing = network.display(HANDHELD_ID);
        if (existing.isPresent() && !existing.get().maps().isEmpty() && Bukkit.getMap(existing.get().maps().get(0)) != null) {
            return existing.get();
        }
        MapView view = newView(world, true);
        staticDirty = true;
        return new Display(HANDHELD_ID, Display.Kind.MAP, null, List.of(), null, 1, 1, List.of(), List.of(view.getId()), null);
    }

    public ItemStack handheldItem(Display display, String label) {
        MapView view = Bukkit.getMap(display.maps().get(0));
        if (view == null) {
            throw new IllegalStateException("The hand-held map no longer exists");
        }
        return mapItem(view, label);
    }

    private MapView newView(World world, boolean contextual) {
        MapView view = Bukkit.createMap(world);
        TileRenderer renderer = new TileRenderer(contextual);
        prepare(view, renderer);
        renderers.put(view.getId(), renderer);
        return view;
    }

    private static ItemStack mapItem(MapView view, String label) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        meta.itemName(Component.text(label));
        item.setItemMeta(meta);
        return item;
    }

    // ---------------------------------------------------------------- removing

    /** Empties the display's frames or removes its hologram. Returns what could not be found. */
    public List<String> remove(Display display) {
        List<String> notes = new ArrayList<>();
        for (Display.Frame frame : display.frames()) {
            World world = Bukkit.getWorld(frame.at().world());
            if (world == null) {
                notes.add("world " + frame.at().world() + " is not loaded; the frame at " + frame.at().coordinates() + " keeps its map");
                continue;
            }
            world.getChunkAt(frame.at().x() >> 4, frame.at().z() >> 4);
            boolean found = false;
            BoundingBox block = new BoundingBox(frame.at().x(), frame.at().y(), frame.at().z(),
                    frame.at().x() + 1, frame.at().y() + 1, frame.at().z() + 1);
            for (Entity entity : world.getNearbyEntities(block, candidate -> candidate instanceof ItemFrame)) {
                ItemFrame itemFrame = (ItemFrame) entity;
                ItemStack item = itemFrame.getItem();
                if (item.getItemMeta() instanceof MapMeta meta && meta.hasMapView() && meta.getMapView() != null
                        && meta.getMapView().getId() == frame.map()) {
                    itemFrame.setItem(new ItemStack(Material.AIR), false);
                    itemFrame.setFixed(false);
                    found = true;
                }
            }
            if (!found) {
                notes.add("no frame with map " + frame.map() + " at " + frame.at().coordinates());
            }
            TileRenderer renderer = renderers.remove(frame.map());
            MapView view = Bukkit.getMap(frame.map());
            if (renderer != null && view != null) {
                view.removeRenderer(renderer);
            }
        }
        for (int id : display.maps()) {
            TileRenderer renderer = renderers.remove(id);
            MapView view = Bukkit.getMap(id);
            if (renderer != null && view != null) {
                view.removeRenderer(renderer);
            }
        }
        if (display.hologram() != null) {
            TextDisplay entity = hologramEntity(display, display.hologram(), false);
            if (entity != null) {
                entity.remove();
            } else {
                notes.add("the hologram entity was not found (chunk not loaded?); it is removed when its chunk loads next");
            }
            hologramEntities.remove(display.id());
        }
        liveSignatures.remove(display.id());
        return notes;
    }

    /** Problems with a display as the world is now, for lists. */
    public List<String> check(Display display) {
        List<String> problems = new ArrayList<>();
        for (int id : display.allMaps()) {
            if (Bukkit.getMap(id) == null) {
                problems.add("map " + id + " no longer exists");
            }
        }
        return problems;
    }

    /** Removes stray hologram entities whose display no longer exists, in loaded chunks of {@code world}. */
    public int removeStrays(World world, Network network) {
        int removed = 0;
        for (TextDisplay entity : world.getEntitiesByClass(TextDisplay.class)) {
            String tag = entity.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING);
            if (tag != null && network.display(tag).isEmpty()) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    // ---------------------------------------------------------------- renderer

    /** Draws one tile onto a map, once per change, and the holder's position on hand-held maps. */
    private static final class TileRenderer extends MapRenderer {
        private volatile @Nullable BufferedImage image;
        private volatile DisplayRenderer.@Nullable Projection projection;
        private volatile @Nullable String world;
        private volatile int version;
        private final Map<MapCanvas, Integer> drawn = new WeakHashMap<>();

        TileRenderer(boolean contextual) {
            super(contextual);
        }

        void show(BufferedImage tile, DisplayRenderer.@Nullable Projection newProjection, @Nullable String newWorld) {
            image = tile;
            projection = newProjection;
            world = newWorld;
            version++;
        }

        @Override
        public void render(MapView view, MapCanvas canvas, Player player) {
            BufferedImage current = image;
            if (current == null) {
                return;
            }
            Integer seen = drawn.get(canvas);
            if (seen == null || seen != version) {
                canvas.drawImage(0, 0, current);
                drawn.put(canvas, version);
            }
            DisplayRenderer.Projection currentProjection = projection;
            if (!isContextual() || currentProjection == null) {
                return;
            }
            MapCursorCollection cursors = new MapCursorCollection();
            Location at = player.getLocation();
            if (at.getWorld() != null && at.getWorld().getName().equals(world) && currentProjection.covers(at.getX(), at.getZ())) {
                int x = (int) Math.round(currentProjection.pixelX(at.getX()) * 2) - 128;
                int y = (int) Math.round(currentProjection.pixelY(at.getZ()) * 2) - 128;
                byte direction = (byte) Math.floorMod(Math.round(at.getYaw() * 16f / 360f), 16);
                cursors.addCursor(new MapCursor((byte) Math.max(-128, Math.min(127, x)), (byte) Math.max(-128, Math.min(127, y)),
                        direction, MapCursor.Type.PLAYER, true));
            }
            canvas.setCursors(cursors);
        }
    }

    /** For tests and status: how many maps carry a renderer. */
    public int attachedMaps() {
        return renderers.size();
    }

    /** The ids of displays in insertion order with their descriptions, for status lines. */
    public static Map<String, String> describe(Network network) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Display display : network.displays().values()) {
            result.put(display.id(), display.describe(network));
        }
        return result;
    }
}
