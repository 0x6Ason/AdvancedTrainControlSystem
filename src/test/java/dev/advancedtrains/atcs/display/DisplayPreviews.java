package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.NetworkCodec;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.Station;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders sample displays from the staging network fixture for the documentation, in map colours:
 * {@code ./gradlew generatePreviews} writes them under {@code docs/images/displays}.
 */
public final class DisplayPreviews {
    private DisplayPreviews() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "docs/images/displays");
        Files.createDirectories(out);
        String yaml = Files.readString(Path.of("src/test/resources/fixtures/staging/network.yml"));
        Network network = new NetworkCodec().read(yaml).network();
        Service local = network.service(ServiceKey.parse("1/to-main")).orElseThrow();
        Service express = network.service(ServiceKey.parse("a/to-new-village")).orElseThrow();
        write(out, "roadmap-6x1", RoadmapRenderer.draw(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 768, 128));
        write(out, "roadmap-4x3", RoadmapRenderer.draw(network, local, "golden-islands", RoadmapRenderer.Flow.RIGHT, 512, 384));
        write(out, "roadmap-express-6x1", RoadmapRenderer.draw(network, express, "main", RoadmapRenderer.Flow.RIGHT, 768, 128));
        List<StationSignRenderer.LineMark> mainLines = network.linesCallingAt("main").stream()
                .map(line -> new StationSignRenderer.LineMark(line.shortName(), line.color(), line.name())).toList();
        write(out, "sign-10x1", StationSignRenderer.draw("Main St.", mainLines, null, StationSignRenderer.Style.MTA, 1280, 128));
        write(out, "sign-1x1", StationSignRenderer.draw("Main St.", mainLines, null, StationSignRenderer.Style.MTA, 128, 128));
        write(out, "sign-hong-kong-3x1", StationSignRenderer.draw("Main St.", mainLines, null, StationSignRenderer.Style.MTR, 384, 128));
        List<SplitFlapRenderer.Row> rows = List.of(
                new SplitFlapRenderer.Row("NOW", "1", 0x1E88E5, "Old Village St.", "2", SplitFlapRenderer.Remark.BOARDING),
                new SplitFlapRenderer.Row("2 MIN", "A", 0xE53935, "New Village St.", "1", SplitFlapRenderer.Remark.NONE),
                new SplitFlapRenderer.Row("6 MIN", "S", 0x8E24AA, "1st Street", "1", SplitFlapRenderer.Remark.DELAYED));
        write(out, "board-4x2", SplitFlapRenderer.image("Main St.", "Platforms 1, 2", "14:05", rows, "No departures", 512, 256));
        write(out, "board-2x1", SplitFlapRenderer.image("Main St.", "Platform 1", "14:05", rows, "No departures", 256, 128));
        List<DisplayRenderer.StatusRow> status = new ArrayList<>();
        for (Line line : network.lines().values()) {
            status.add(new DisplayRenderer.StatusRow(line.shortName(), line.color(), line.name(),
                    line.id().equals("s") ? "Suspended: track works" : line.suspension()));
        }
        write(out, "status-2x1", DisplayRenderer.status("Service status", status, 256, 128));
        // Without recorded track here, the map joins the stations of each service with straight lines.
        List<DisplayRenderer.MapLine> lines = new ArrayList<>();
        List<DisplayRenderer.MapStation> stations = new ArrayList<>();
        for (Station station : network.stations().values()) {
            station.markerPosition().ifPresent(position -> stations.add(new DisplayRenderer.MapStation(station.id(),
                    station.name(), position.x(), position.z(),
                    network.linesCallingAt(station.id()).stream().map(Line::color).toList(), false)));
        }
        for (Service service : network.services()) {
            List<double[]> points = new ArrayList<>();
            for (int index = 0; index < service.size(); index++) {
                network.station(service.stop(index).stationId()).flatMap(Station::markerPosition)
                        .ifPresent(position -> points.add(new double[] {position.x(), position.z()}));
            }
            lines.add(new DisplayRenderer.MapLine(network.line(service.key().lineId()).orElseThrow().color(), points));
        }
        write(out, "map-2x2", DisplayRenderer.networkMap("", lines, stations, "main", 256, 256).image());
        System.out.println("wrote previews to " + out);
    }

    /** Writes the picture as an item frame shows it, in map colours. */
    private static void write(Path out, String name, BufferedImage image) throws IOException {
        ImageIO.write(MapPixels.of(image).toImage(), "png", out.resolve(name + ".png").toFile());
    }
}
