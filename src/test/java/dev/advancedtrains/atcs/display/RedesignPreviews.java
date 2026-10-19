package dev.advancedtrains.atcs.display;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Platform;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import org.bukkit.map.MapPalette;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the redesigned displays from a website {@code network.json} (the live network as the
 * website sees it), each through the map colour palette as an item frame shows it, and writes a
 * manifest for the review page. Arguments: the network.json, the output folder.
 */
public final class RedesignPreviews {
    private static final int T = 128;
    private static final List<Map<String, Object>> MANIFEST = new ArrayList<>();
    private static Path out;

    private RedesignPreviews() {
    }

    public static void main(String[] args) throws IOException {
        Network network = network(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        out = Path.of(args[1]);
        Files.createDirectories(out);

        // ---- departure boards
        List<SplitFlapRenderer.Row> now = List.of(
                new SplitFlapRenderer.Row("NOW", "1", color(network, "1"), "Old Village St.", "4", SplitFlapRenderer.Remark.BOARDING),
                new SplitFlapRenderer.Row("2 MIN", "A", color(network, "a"), "New Village St.", "2", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("4 MIN", "1", color(network, "1"), "Old Village St.", "5", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("7 MIN", "A", color(network, "a"), "New Village St.", "3", SplitFlapRenderer.Remark.DELAYED),
                new SplitFlapRenderer.Row("12 MIN", "1", color(network, "1"), "Old Village St.", "4", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("15 MIN", "A", color(network, "a"), "New Village St.", "2", SplitFlapRenderer.Remark.NONE));
        List<SplitFlapRenderer.Row> next = List.of(
                new SplitFlapRenderer.Row("1 MIN", "A", color(network, "a"), "New Village St.", "2", SplitFlapRenderer.Remark.ARRIVING),
                new SplitFlapRenderer.Row("3 MIN", "1", color(network, "1"), "Old Village St.", "5", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("6 MIN", "A", color(network, "a"), "New Village St.", "3", SplitFlapRenderer.Remark.DELAYED),
                new SplitFlapRenderer.Row("11 MIN", "1", color(network, "1"), "Old Village St.", "4", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("14 MIN", "A", color(network, "a"), "New Village St.", "2", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("18 MIN", "1", color(network, "1"), "Old Village St.", "4", SplitFlapRenderer.Remark.NONE));
        int[][] boards = {{2, 1}, {3, 2}, {4, 2}, {6, 3}, {8, 2}};
        for (int[] size : boards) {
            int w = size[0] * T;
            int h = size[1] * T;
            emit("board", "board-" + size[0] + "x" + size[1], size, "Main St. departures, " + size[0] + "×" + size[1] + " frames",
                    SplitFlapRenderer.image("Main St.", "Platforms 2–5", "14:05", now, "No departures", w, h));
        }
        // The turn from one set of departures to the next, frame by frame.
        int w = 4 * T;
        int h = 2 * T;
        List<String> frames = new ArrayList<>();
        for (int step = 0; step <= SplitFlapRenderer.steps(); step++) {
            String name = "board-turn-" + step;
            BufferedImage image = SplitFlapRenderer.image("Main St.", "Platforms 2–5", "14:06",
                    SplitFlapRenderer.between(now, next, step, w, h), "No departures", w, h);
            write(name, image);
            frames.add(name + ".png");
        }
        Map<String, Object> turn = new LinkedHashMap<>();
        turn.put("group", "board-animation");
        turn.put("frames", frames);
        turn.put("columns", 4);
        turn.put("rows", 2);
        MANIFEST.add(turn);
        emit("board", "board-empty-3x1", new int[] {3, 1}, "Late at night, 3×1",
                SplitFlapRenderer.image("YSYSign Station", null, "02:40", List.of(), "No further departures", 3 * T, T));

        // ---- roadmaps
        Service local = network.service(ServiceKey.parse("1/to-main")).orElseThrow();
        Service express = network.service(ServiceKey.parse("1/to-old-village-2")).orElseThrow();
        Service fiveCw = network.service(ServiceKey.parse("5/cw")).orElseThrow();
        Service nerdyCw = network.service(ServiceKey.parse("nerdy/cw")).orElseThrow();
        Service aLocal = network.service(ServiceKey.parse("a/to-main")).orElseThrow();
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 10, 1, "Line 1 to Main St. at Golden Islands, train leaves to the right");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.LEFT, 10, 1, "The same, train leaves to the left");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 6, 1, "Line 1, 6×1");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 4, 1, "Line 1, 4×1 (crowded: names slant)");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 8, 2, "Line 1, 8×2");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 4, 3, "Line 1, 4×3 wall: folded into rows");
        emit("roadmap", "roadmap-1-to-main-4x3-column", new int[] {4, 3}, "Line 1, 4×3 wall as a column instead",
                RoadmapRenderer.draw(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, RoadmapRenderer.Shape.COLUMN, 4 * T, 3 * T));
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.LEFT, 4, 3, "Line 1, 4×3, leaving to the left");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 2, 2, "Line 1, 2×2");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 1, 3, "Line 1, 1×3 pillar");
        roadmap(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 2, 4, "Line 1, 2×4 pillar");
        roadmap(network, express, "frostpeak", RoadmapRenderer.Flow.RIGHT, 6, 1, "Line 1 Express at Frostpeak");
        roadmap(network, aLocal, "nerdy", RoadmapRenderer.Flow.RIGHT, 6, 1, "Line A Local at Nerdy St.");
        roadmap(network, fiveCw, "ysysign", RoadmapRenderer.Flow.RIGHT, 6, 1, "Line 5 Clockwise (a loop) at YSYSign");
        roadmap(network, fiveCw, "ysysign", RoadmapRenderer.Flow.RIGHT, 3, 2, "Line 5 Clockwise, 3×2");
        roadmap(network, fiveCw, "ysysign", RoadmapRenderer.Flow.RIGHT, 2, 2, "Line 5 Clockwise, 2×2");
        roadmap(network, nerdyCw, "nerdy-estate-blk-1", RoadmapRenderer.Flow.RIGHT, 10, 1, "Nerdy loop Clockwise at Nerdy Estate BLK. 1");
        roadmap(network, nerdyCw, "nerdy-estate-blk-1", RoadmapRenderer.Flow.RIGHT, 4, 3, "Nerdy loop, 4×3");

        // ---- station signs
        for (StationSignRenderer.Style style : StationSignRenderer.Style.values()) {
            sign(network, "main", null, style, 10, 1, "Main St., 10×1");
            sign(network, "main", "Platform 4  ·  Line 1 to Old Village St.", style, 10, 1, "With a platform note, 10×1");
            sign(network, "golden-beach", null, style, 3, 1, "Golden Beach St., 3×1");
            sign(network, "nerdy-estate-blk-1", null, style, 3, 1, "A long name, 3×1");
            sign(network, "main", null, style, 1, 1, "Main St., 1×1");
            sign(network, "nerdy", null, style, 1, 1, "Nerdy St., 1×1");
            sign(network, "ysysign", null, style, 2, 2, "YSYSign Station, 2×2");
            sign(network, "liberty", null, style, 4, 3, "Liberty St., 4×3 wall");
            sign(network, "golden-beach", null, style, 1, 3, "Golden Beach St., 1×3 pillar");
        }

        Files.writeString(out.resolve("manifest.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(MANIFEST));
        System.out.println("wrote " + MANIFEST.size() + " previews to " + out);
    }

    private static void roadmap(Network network, Service service, String here, RoadmapRenderer.Flow flow, int columns, int rows,
                                String title) throws IOException {
        String name = "roadmap-" + service.key().toString().replace('/', '-') + "-" + columns + "x" + rows + "-" + flow.name().toLowerCase();
        emit("roadmap", name, new int[] {columns, rows}, title,
                RoadmapRenderer.draw(network, service, here, flow, columns * T, rows * T));
    }

    private static void sign(Network network, String stationId, String note, StationSignRenderer.Style style, int columns, int rows,
                             String title) throws IOException {
        List<StationSignRenderer.LineMark> lines = new ArrayList<>();
        for (Line line : network.linesCallingAt(stationId)) {
            lines.add(new StationSignRenderer.LineMark(line.shortName(), line.color(), line.name()));
        }
        String name = "sign-" + style.name().toLowerCase() + "-" + stationId + "-" + columns + "x" + rows + (note == null ? "" : "-note");
        emit("sign-" + style.name().toLowerCase(), name, new int[] {columns, rows}, title,
                StationSignRenderer.draw(network.stationName(stationId), lines, note, style, columns * T, rows * T));
    }

    private static void emit(String group, String name, int[] size, String title, BufferedImage image) throws IOException {
        write(name, image);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("group", group);
        entry.put("file", name + ".png");
        entry.put("title", title);
        entry.put("columns", size[0]);
        entry.put("rows", size[1]);
        MANIFEST.add(entry);
    }

    /** Writes the picture as an item frame shows it: every pixel turned into the nearest map colour. */
    @SuppressWarnings({"deprecation", "removal"})
    private static void write(String name, BufferedImage image) throws IOException {
        BufferedImage mapped = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Map<Integer, Integer> cache = new java.util.HashMap<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                int shown = cache.computeIfAbsent(rgb, value -> MapPalette.getColor(MapPalette.matchColor(new Color(value))).getRGB());
                mapped.setRGB(x, y, shown);
            }
        }
        ImageIO.write(mapped, "png", out.resolve(name + ".png").toFile());
    }

    private static int color(Network network, String lineId) {
        return network.line(lineId).orElseThrow().color();
    }

    /** The network as the website describes it: stations, lines and services with their stops. */
    static Network network(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Network.Builder builder = Network.builder();
        for (JsonElement element : root.getAsJsonArray("stations")) {
            JsonObject station = element.getAsJsonObject();
            Map<String, Platform> platforms = new LinkedHashMap<>();
            JsonArray ids = station.has("platforms") ? station.getAsJsonArray("platforms") : new JsonArray();
            for (JsonElement id : ids) {
                platforms.put(id.getAsString(), new Platform(id.getAsString(), "", List.of()));
            }
            builder.station(new Station(station.get("id").getAsString(), station.get("name").getAsString(), platforms, null, null, null));
        }
        for (JsonElement element : root.getAsJsonArray("lines")) {
            JsonObject lineJson = element.getAsJsonObject();
            int rgb = Integer.parseInt(lineJson.get("color").getAsString().substring(1), 16);
            Line line = Line.of(lineJson.get("id").getAsString(), lineJson.get("name").getAsString(), rgb);
            if (lineJson.has("short") && !lineJson.get("short").isJsonNull()) {
                line = line.withShortName(lineJson.get("short").getAsString());
            }
            for (JsonElement serviceElement : lineJson.getAsJsonArray("services")) {
                JsonObject service = serviceElement.getAsJsonObject();
                List<Stop> stops = new ArrayList<>();
                for (JsonElement stopElement : service.getAsJsonArray("stops")) {
                    JsonObject stop = stopElement.getAsJsonObject();
                    boolean pass = stop.has("pass") && stop.get("pass").getAsBoolean();
                    String platform = stop.has("platform") && !stop.get("platform").isJsonNull() ? ":" + stop.get("platform").getAsString() : "";
                    stops.add(Stop.parse((pass ? "~" : "") + stop.get("station").getAsString() + platform));
                }
                Service built = Service.of(ServiceKey.parse(service.get("key").getAsString()), service.get("name").getAsString(), stops)
                        .withCircular(service.has("circular") && service.get("circular").getAsBoolean());
                line = line.withService(built);
            }
            builder.line(line);
        }
        return builder.build();
    }
}
