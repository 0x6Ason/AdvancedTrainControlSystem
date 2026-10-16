package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Station;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The logos a railway shows: generated from the network, or an operator's own picture dropped
 * into {@code logos/} in the plugin folder ({@code <station>.png} for a station,
 * {@code line-<id>.png} for a line). Each logo has a key that changes when its picture would, so
 * callers can cache what they made of it.
 */
public final class Logos {
    private final Path overrides;
    private final Logger logger;

    public Logos(Path overrides, Logger logger) {
        this.overrides = overrides;
        this.logger = logger;
    }

    public Path folder() {
        return overrides;
    }

    /** The station's logo at the size, from the override file when there is one. */
    public BufferedImage station(Network network, Station station, int size) {
        Optional<BufferedImage> override = read(overrideFile(station.id()), size);
        return override.orElseGet(() -> LogoRenderer.station(network.code(station.id()), colors(network, station), size));
    }

    public String stationSvg(Network network, Station station, int size) {
        return LogoRenderer.stationSvg(network.code(station.id()), colors(network, station), size);
    }

    public BufferedImage line(Line line, int size) {
        Optional<BufferedImage> override = read(overrideFile("line-" + line.id()), size);
        return override.orElseGet(() -> LogoRenderer.lineBadge(line.shortName(), line.color(), size));
    }

    public String lineSvg(Line line, int size) {
        return LogoRenderer.lineBadgeSvg(line.shortName(), line.color(), size);
    }

    /** Changes whenever the station's logo would. */
    public String stationKey(Network network, Station station, int size) {
        StringBuilder key = new StringBuilder("station:").append(station.id()).append(':').append(size).append(':')
                .append(network.code(station.id()));
        for (int color : colors(network, station)) {
            key.append(':').append(Integer.toHexString(color));
        }
        return key.append(':').append(stamp(overrideFile(station.id()))).toString();
    }

    public String lineKey(Line line, int size) {
        return "line:" + line.id() + ':' + size + ':' + line.shortName() + ':' + Integer.toHexString(line.color())
                + ':' + stamp(overrideFile("line-" + line.id()));
    }

    /** Whether an operator's own picture is used for the station. */
    public boolean stationOverridden(String stationId) {
        return Files.isRegularFile(overrideFile(stationId));
    }

    public Path overrideFile(String name) {
        return overrides.resolve(name.toLowerCase(Locale.ROOT) + ".png");
    }

    private static List<Integer> colors(Network network, Station station) {
        return network.linesCallingAt(station.id()).stream().map(Line::color).toList();
    }

    private static String stamp(Path file) {
        try {
            return Files.isRegularFile(file) ? Long.toString(Files.getLastModifiedTime(file).toMillis()) : "generated";
        } catch (IOException exception) {
            return "generated";
        }
    }

    private Optional<BufferedImage> read(Path file, int size) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                logger.warning(file.getFileName() + " is not a readable PNG; the generated logo is used");
                return Optional.empty();
            }
            return Optional.of(scaled(image, size));
        } catch (IOException exception) {
            logger.log(Level.WARNING, "Could not read " + file.getFileName() + "; the generated logo is used", exception);
            return Optional.empty();
        }
    }

    static BufferedImage scaled(BufferedImage source, int size) {
        if (source.getWidth() == size && source.getHeight() == size) {
            return source;
        }
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Reads a PNG from disk if present, for tests and tools. */
    public static @Nullable BufferedImage load(Path file) {
        try {
            return Files.isRegularFile(file) ? ImageIO.read(file.toFile()) : null;
        } catch (IOException exception) {
            return null;
        }
    }
}
