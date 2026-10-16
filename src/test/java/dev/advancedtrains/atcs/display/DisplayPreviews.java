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
 * Renders sample displays from the staging network fixture for the documentation:
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
        write(out, "roadmap-4x1", DisplayRenderer.roadmap(network, local, "golden-islands", 512, 128));
        write(out, "roadmap-4x2", DisplayRenderer.roadmap(network, local, "golden-islands", 512, 256));
        write(out, "roadmap-express-4x1", DisplayRenderer.roadmap(network, express, "main", 512, 128));
        Station main = network.station("main").orElseThrow();
        List<Integer> colors = network.linesCallingAt("main").stream().map(Line::color).toList();
        write(out, "logo-1x1", DisplayRenderer.logo(LogoRenderer.station(network.code("main"), colors, 128), main.name(), 128, 128));
        List<DisplayRenderer.BoardRow> rows = List.of(
                new DisplayRenderer.BoardRow("A", 0xE53935, "New Village St.", "2:05", false, "1"),
                new DisplayRenderer.BoardRow("1", 0x1E88E5, "Old Village St.", "Boarding", true, "2"),
                new DisplayRenderer.BoardRow("S", 0x8E24AA, "1st Street", "Expected", false, "1"));
        write(out, "board-2x2", DisplayRenderer.board("Main St.", "Platform 1, 2", rows, "No departures", 256, 256));
        write(out, "board-2x1", DisplayRenderer.board("Main St.", "Platform 1", rows, "No departures", 256, 128));
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

    private static void write(Path out, String name, BufferedImage image) throws IOException {
        ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
    }
}
