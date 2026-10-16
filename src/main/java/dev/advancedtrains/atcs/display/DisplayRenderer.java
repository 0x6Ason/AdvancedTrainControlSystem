package dev.advancedtrains.atcs.display;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.Station;
import dev.advancedtrains.atcs.network.Stop;
import org.jspecify.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the pictures displays show: strip-diagram roadmaps, logos, departure boards, the network
 * map and a service status panel. Pure Java2D so it is testable without a server; the
 * {@link DisplayService} cuts the result into 128 px map tiles.
 */
public final class DisplayRenderer {
    /** The size of one map item, in pixels. */
    public static final int TILE = 128;

    static final Color PAPER = Color.WHITE;
    static final Color INK = new Color(0x1F2328);
    static final Color MUTED = new Color(0x6E7781);
    static final Color FAINT = new Color(0xD0D7DE);
    static final Color GOOD = new Color(0x1A7F37);
    static final Color BAD = new Color(0xCF222E);
    static final Color HERE = new Color(0xCF222E);

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private DisplayRenderer() {
    }

    // ---------------------------------------------------------------- roadmap

    /**
     * The strip diagram of a service: every stop on a line in the line colour, interchanges with
     * the badges of the other lines, run-through stops as small ticks, the direction of travel,
     * and "You are here" at {@code hereStationId}.
     */
    public static BufferedImage roadmap(Network network, Service service, @Nullable String hereStationId, int width, int height) {
        Line line = network.line(service.key().lineId()).orElse(Line.of(service.key().lineId(), service.key().lineId(), 0x9E9E9E));
        BufferedImage image = paper(width, height);
        Graphics2D g = graphics(image);
        try {
            Color color = new Color(line.color());
            double pad = Math.max(4, height / 18.0);
            double headerHeight = height * 0.2;
            double badge = Math.max(8, height * 0.13);
            BufferedImage badgeImage = LogoRenderer.lineBadge(line.shortName(), line.color(), (int) Math.round(badge));
            g.drawImage(badgeImage, (int) Math.round(pad), (int) Math.round(pad), null);
            String destination = service.destination().map(stop -> network.stationName(stop.stationId())).orElse(service.name());
            Font headerFont = bold(badge * 0.62);
            double arrowWidth = badge * 0.8;
            double toWidth = Math.min(width * 0.45, textWidth(g, headerFont, "to " + destination) + arrowWidth + pad);
            Font toFont = fit(g, headerFont, "to " + destination, toWidth - arrowWidth - pad / 2);
            double toX = width - pad - textWidth(g, toFont, "to " + destination);
            drawString(g, "to " + destination, toX, pad + badge * 0.5 + ascent(g, toFont) / 2.5, toFont, INK);
            String title = line.name() + "  " + service.name();
            double titleX = pad + badge + pad * 0.8;
            Font titleFont = fit(g, headerFont, title, toX - arrowWidth - titleX - pad);
            drawString(g, title, titleX, pad + badge * 0.5 + ascent(g, titleFont) / 2.5, titleFont, INK);
            arrow(g, toX - arrowWidth - pad / 4, pad + badge * 0.5, arrowWidth, badge * 0.5, color);

            List<Stop> stops = service.stops();
            int count = stops.size();
            double lineY = height * 0.72;
            double thickness = Math.max(3, height / 15.0);
            double namesTop = headerHeight + pad * 0.5;
            double radius = thickness * 0.8;
            double diagonal = (lineY - radius - pad * 0.4 - namesTop) / Math.sin(Math.toRadians(55));
            // Names run up and to the right, so the last stop needs room on the right for its name.
            String lastName = count == 0 ? "" : network.stationName(stops.get(count - 1).stationId());
            double nameSizeGuess = Math.max(6, Math.min(height * 0.09, (width - 2 * pad) / Math.max(1, count - 1) * 1.35));
            double lastNameWidth = Math.min(diagonal, textWidth(g, bold(nameSizeGuess), lastName));
            double rightRoom = Math.min(width * 0.2, lastNameWidth * Math.cos(Math.toRadians(55)));
            double left = pad + thickness;
            double right = width - pad - Math.max(thickness * 2.2, rightRoom);
            // The line itself, with an arrowhead showing the direction of travel.
            g.setColor(color);
            g.setStroke(new BasicStroke((float) thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(left, lineY, right + thickness * 0.6, lineY));
            arrow(g, right + thickness * 0.3, lineY, thickness * 1.9, thickness * 1.8, color);

            double step = count > 1 ? (right - left) / (count - 1) : 0;
            double nameSize = Math.max(6, Math.min(height * 0.09, step * 1.35));
            Font nameFont = bold(nameSize);
            int longest = 0;
            for (Stop stop : stops) {
                longest = Math.max(longest, (int) Math.ceil(textWidth(g, nameFont, network.stationName(stop.stationId()))));
            }
            if (longest > diagonal) {
                nameFont = bold(Math.max(5, nameSize * diagonal / longest));
            }
            Font badgeFont = bold(Math.max(5, thickness * 0.9));
            for (int index = 0; index < count; index++) {
                Stop stop = stops.get(index);
                double x = left + index * step;
                boolean here = stop.stationId().equals(hereStationId);
                List<Line> others = new ArrayList<>(network.linesCallingAt(stop.stationId()));
                others.removeIf(other -> other.id().equals(line.id()));
                boolean terminus = index == 0 || index == count - 1;
                if (!stop.stops()) {
                    // A run-through stop: a tick across the line and a muted name.
                    g.setColor(PAPER);
                    g.setStroke(new BasicStroke((float) Math.max(1, thickness * 0.22)));
                    g.draw(new Line2D.Double(x, lineY - thickness * 0.5, x, lineY + thickness * 0.5));
                    rotatedName(g, network.stationName(stop.stationId()), x, lineY - thickness * 0.7, nameFont, MUTED);
                    continue;
                }
                if (here) {
                    g.setColor(HERE);
                    g.fill(new Ellipse2D.Double(x - radius * 1.25, lineY - radius * 1.25, radius * 2.5, radius * 2.5));
                    g.setColor(PAPER);
                    g.fill(new Ellipse2D.Double(x - radius * 0.45, lineY - radius * 0.45, radius * 0.9, radius * 0.9));
                } else if (!others.isEmpty() || terminus) {
                    g.setColor(PAPER);
                    g.fill(new Ellipse2D.Double(x - radius, lineY - radius, radius * 2, radius * 2));
                    g.setColor(INK);
                    g.setStroke(new BasicStroke((float) Math.max(1.2, thickness * 0.28)));
                    g.draw(new Ellipse2D.Double(x - radius, lineY - radius, radius * 2, radius * 2));
                } else {
                    double small = radius * 0.62;
                    g.setColor(PAPER);
                    g.fill(new Ellipse2D.Double(x - small, lineY - small, small * 2, small * 2));
                }
                rotatedName(g, network.stationName(stop.stationId()), x, lineY - radius - pad * 0.35, nameFont, here ? HERE : INK);
                double below = lineY + radius + pad * 0.3;
                if (here) {
                    Font hereFont = bold(Math.max(5, thickness * 0.95));
                    String label = "YOU ARE HERE";
                    double labelWidth = textWidth(g, hereFont, label);
                    double labelX = Math.max(pad, Math.min(width - pad - labelWidth, x - labelWidth / 2));
                    drawString(g, label, labelX, below + ascent(g, hereFont), hereFont, HERE);
                } else if (!others.isEmpty()) {
                    double size = thickness * 1.15;
                    double total = others.size() * size + (others.size() - 1) * 1;
                    double start = Math.max(pad, Math.min(width - pad - total, x - total / 2));
                    for (int other = 0; other < others.size(); other++) {
                        Line otherLine = others.get(other);
                        double bx = start + other * (size + 1);
                        g.setColor(new Color(otherLine.color()));
                        g.fill(new RoundRectangle2D.Double(bx, below, size, size, size * 0.4, size * 0.4));
                        Font fitted = fit(g, badgeFont, otherLine.shortName(), size * 0.9);
                        double textWidth = textWidth(g, fitted, otherLine.shortName());
                        drawString(g, otherLine.shortName(), bx + (size - textWidth) / 2, below + size / 2 + ascent(g, fitted) / 2.6,
                                fitted, LogoRenderer.onColor(otherLine.color()));
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ---------------------------------------------------------------- logo

    /** A station logo centred on white; {@code roundel} is the picture {@link Logos} provides. */
    public static BufferedImage logo(BufferedImage roundel, @Nullable String caption, int width, int height) {
        BufferedImage image = paper(width, height);
        Graphics2D g = graphics(image);
        try {
            double pad = Math.max(4, Math.min(width, height) / 16.0);
            double captionHeight = caption == null ? 0 : Math.max(8, height * 0.14);
            double size = Math.min(width - 2 * pad, height - 2 * pad - captionHeight);
            double x = (width - size) / 2;
            double y = pad;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(roundel, (int) Math.round(x), (int) Math.round(y), (int) Math.round(size), (int) Math.round(size), null);
            if (caption != null) {
                Font font = fit(g, bold(captionHeight * 0.7), caption, width - 2 * pad);
                double textWidth = textWidth(g, font, caption);
                drawString(g, caption, (width - textWidth) / 2, y + size + captionHeight * 0.75, font, INK);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ---------------------------------------------------------------- board

    /** One departure as a board picture shows it. */
    public record BoardRow(String badge, int color, String destination, String status, boolean statusGood,
                           @Nullable String platform) {
    }

    /** The next departures as a picture: a header, then one row per departure. */
    public static BufferedImage board(String title, @Nullable String subtitle, List<BoardRow> rows, String emptyText,
                                      int width, int height) {
        BufferedImage image = paper(width, height);
        Graphics2D g = graphics(image);
        try {
            double pad = Math.max(4, Math.min(width, height) / 20.0);
            double headerHeight = Math.max(12, height * 0.16);
            g.setColor(INK);
            g.fill(new Rectangle2D.Double(0, 0, width, headerHeight));
            Font titleFont = fit(g, bold(headerHeight * 0.55), title, width * 0.62);
            drawString(g, title, pad, headerHeight / 2 + ascent(g, titleFont) / 2.6, titleFont, PAPER);
            if (subtitle != null && !subtitle.isEmpty()) {
                Font subtitleFont = fit(g, plain(headerHeight * 0.42), subtitle, width * 0.34);
                double subtitleWidth = textWidth(g, subtitleFont, subtitle);
                drawString(g, subtitle, width - pad - subtitleWidth, headerHeight / 2 + ascent(g, subtitleFont) / 2.6,
                        subtitleFont, FAINT);
            }
            if (rows.isEmpty()) {
                Font font = fit(g, plain(Math.max(8, height * 0.1)), emptyText, width - 2 * pad);
                double textWidth = textWidth(g, font, emptyText);
                drawString(g, emptyText, (width - textWidth) / 2, headerHeight + (height - headerHeight) / 2, font, MUTED);
                return image;
            }
            double rowHeight = Math.min(Math.max(10, height * 0.17), (height - headerHeight - pad) / rows.size());
            double y = headerHeight + pad / 2;
            for (BoardRow row : rows) {
                double badge = rowHeight * 0.72;
                BufferedImage badgeImage = LogoRenderer.lineBadge(row.badge(), row.color(), (int) Math.round(badge));
                g.drawImage(badgeImage, (int) Math.round(pad), (int) Math.round(y + (rowHeight - badge) / 2), null);
                Font statusFont = plain(rowHeight * 0.5);
                double statusWidth = textWidth(g, statusFont, row.status());
                double platformWidth = 0;
                if (row.platform() != null) {
                    Font platformFont = plain(rowHeight * 0.42);
                    String platform = "P" + row.platform();
                    platformWidth = textWidth(g, platformFont, platform) + pad;
                    drawString(g, platform, width - pad - platformWidth + pad, y + rowHeight / 2 + ascent(g, platformFont) / 2.6,
                            platformFont, MUTED);
                }
                double statusX = width - pad - platformWidth - statusWidth;
                drawString(g, row.status(), statusX, y + rowHeight / 2 + ascent(g, statusFont) / 2.6, statusFont,
                        row.statusGood() ? GOOD : INK);
                double destinationX = pad + badge + pad * 0.8;
                Font destinationFont = fit(g, bold(rowHeight * 0.55), row.destination(), statusX - pad - destinationX);
                drawString(g, row.destination(), destinationX, y + rowHeight / 2 + ascent(g, destinationFont) / 2.6,
                        destinationFont, INK);
                g.setColor(FAINT);
                g.setStroke(new BasicStroke(1));
                g.draw(new Line2D.Double(pad, y + rowHeight - 1, width - pad, y + rowHeight - 1));
                y += rowHeight;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ---------------------------------------------------------------- status

    /** One line on the status panel. */
    public record StatusRow(String badge, int color, String name, @Nullable String notice) {
    }

    /** Every line and whether it runs. */
    public static BufferedImage status(String title, List<StatusRow> rows, int width, int height) {
        BufferedImage image = paper(width, height);
        Graphics2D g = graphics(image);
        try {
            double pad = Math.max(4, Math.min(width, height) / 20.0);
            double headerHeight = Math.max(12, height * 0.16);
            g.setColor(INK);
            g.fill(new Rectangle2D.Double(0, 0, width, headerHeight));
            Font titleFont = fit(g, bold(headerHeight * 0.55), title, width - 2 * pad);
            drawString(g, title, pad, headerHeight / 2 + ascent(g, titleFont) / 2.6, titleFont, PAPER);
            if (rows.isEmpty()) {
                return image;
            }
            double rowHeight = Math.min(Math.max(10, height * 0.17), (height - headerHeight - pad) / rows.size());
            double y = headerHeight + pad / 2;
            for (StatusRow row : rows) {
                double badge = rowHeight * 0.72;
                BufferedImage badgeImage = LogoRenderer.lineBadge(row.badge(), row.color(), (int) Math.round(badge));
                g.drawImage(badgeImage, (int) Math.round(pad), (int) Math.round(y + (rowHeight - badge) / 2), null);
                String state = row.notice() == null ? "Good service" : row.notice();
                Font stateFont = fit(g, plain(rowHeight * 0.42), state, width * 0.36);
                double stateWidth = textWidth(g, stateFont, state);
                drawString(g, state, width - pad - stateWidth, y + rowHeight / 2 + ascent(g, stateFont) / 2.6, stateFont,
                        row.notice() == null ? GOOD : BAD);
                double nameX = pad + badge + pad * 0.8;
                Font nameFont = fit(g, bold(rowHeight * 0.55), row.name(), width - pad - stateWidth - pad - nameX);
                drawString(g, row.name(), nameX, y + rowHeight / 2 + ascent(g, nameFont) / 2.6, nameFont, INK);
                y += rowHeight;
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    // ---------------------------------------------------------------- network map

    /** A line's track on the map: world x and z pairs. */
    public record MapLine(int color, List<double[]> points) {
    }

    /** A station on the map. */
    public record MapStation(String id, String name, double x, double z, List<Integer> colors, boolean terminus) {
    }

    /** Maps world coordinates to picture pixels. */
    public record Projection(double scale, double offsetX, double offsetZ, double minX, double minZ, double maxX, double maxZ) {
        public double pixelX(double worldX) {
            return offsetX + (worldX - minX) * scale;
        }

        public double pixelY(double worldZ) {
            return offsetZ + (worldZ - minZ) * scale;
        }

        public boolean covers(double worldX, double worldZ) {
            return worldX >= minX && worldX <= maxX && worldZ >= minZ && worldZ <= maxZ;
        }
    }

    /** The picture and how it was projected, so a hand-held copy can place a cursor. */
    public record NetworkMap(BufferedImage image, Projection projection) {
    }

    /** The network drawn along its track, north up, scaled to fit. */
    public static NetworkMap networkMap(String title, List<MapLine> lines, List<MapStation> stations, @Nullable String hereId,
                                        int width, int height) {
        BufferedImage image = paper(width, height);
        Graphics2D g = graphics(image);
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (MapLine line : lines) {
            for (double[] point : line.points()) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minZ = Math.min(minZ, point[1]);
                maxZ = Math.max(maxZ, point[1]);
            }
        }
        for (MapStation station : stations) {
            minX = Math.min(minX, station.x());
            maxX = Math.max(maxX, station.x());
            minZ = Math.min(minZ, station.z());
            maxZ = Math.max(maxZ, station.z());
        }
        if (stations.isEmpty() && lines.isEmpty()) {
            minX = 0;
            maxX = 1;
            minZ = 0;
            maxZ = 1;
        }
        double pad = Math.max(6, Math.min(width, height) / 12.0);
        double headerHeight = title.isEmpty() ? 0 : Math.max(10, Math.min(width, height) * 0.1);
        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((width - 2 * pad) / spanX, (height - 2 * pad - headerHeight) / spanZ);
        double offsetX = (width - spanX * scale) / 2;
        double offsetZ = headerHeight + (height - headerHeight - spanZ * scale) / 2;
        Projection projection = new Projection(scale, offsetX, offsetZ, minX, minZ, maxX, maxZ);
        try {
            if (!title.isEmpty()) {
                Font titleFont = fit(g, bold(headerHeight * 0.6), title, width - 2 * pad);
                drawString(g, title, pad / 2, headerHeight * 0.75, titleFont, INK);
            }
            double thickness = Math.max(2, Math.min(width, height) / 70.0);
            for (MapLine line : lines) {
                Path2D.Double path = new Path2D.Double();
                boolean first = true;
                for (double[] point : line.points()) {
                    double x = projection.pixelX(point[0]);
                    double y = projection.pixelY(point[1]);
                    if (first) {
                        path.moveTo(x, y);
                        first = false;
                    } else {
                        path.lineTo(x, y);
                    }
                }
                g.setStroke(new BasicStroke((float) (thickness + 1.5), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(31, 35, 40, 120));
                g.draw(path);
                g.setStroke(new BasicStroke((float) thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(new Color(line.color()));
                g.draw(path);
            }
            double nameSize = Math.max(5, Math.min(width, height) / 32.0);
            Font nameFont = bold(nameSize);
            boolean names = nameSize >= 6 && stations.size() * nameSize * 24 < (double) width * height;
            List<Rectangle2D> used = new ArrayList<>();
            for (MapStation station : stations) {
                double x = projection.pixelX(station.x());
                double y = projection.pixelY(station.z());
                boolean here = station.id().equals(hereId);
                boolean major = station.colors().size() >= 2 || station.terminus();
                double radius = here ? thickness * 1.3 : major ? thickness * 0.95 : thickness * 0.6;
                g.setColor(here ? HERE : PAPER);
                g.fill(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
                g.setColor(here ? PAPER : major ? INK : new Color(station.colors().isEmpty() ? 0x9E9E9E : station.colors().get(0)));
                g.setStroke(new BasicStroke((float) Math.max(1, thickness * 0.35)));
                g.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
                if ((names && major) || here) {
                    String label = here ? station.name() + "  (you are here)" : station.name();
                    double labelWidth = textWidth(g, nameFont, label);
                    double labelX = x + radius + 2;
                    if (labelX + labelWidth > width - 2) {
                        labelX = x - radius - 2 - labelWidth;
                    }
                    Rectangle2D box = new Rectangle2D.Double(labelX - 1, y - nameSize * 0.6, labelWidth + 2, nameSize * 1.2);
                    boolean clash = false;
                    for (Rectangle2D other : used) {
                        if (other.intersects(box)) {
                            clash = true;
                            break;
                        }
                    }
                    if (clash && !here) {
                        continue;
                    }
                    used.add(box);
                    g.setColor(new Color(255, 255, 255, 200));
                    g.fill(box);
                    drawString(g, label, labelX, y + ascent(g, nameFont) / 2.6, nameFont, here ? HERE : INK);
                }
            }
        } finally {
            g.dispose();
        }
        return new NetworkMap(image, projection);
    }

    // ---------------------------------------------------------------- tiles

    /** The 128 px tile at column {@code column}, row {@code row} of a mosaic; blank beyond its edge. */
    public static BufferedImage tile(BufferedImage mosaic, int column, int row) {
        BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tile.createGraphics();
        try {
            g.setColor(PAPER);
            g.fillRect(0, 0, TILE, TILE);
            g.drawImage(mosaic, -column * TILE, -row * TILE, null);
        } finally {
            g.dispose();
        }
        return tile;
    }

    // ---------------------------------------------------------------- helpers

    static BufferedImage paper(int width, int height) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(PAPER);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        } finally {
            g.dispose();
        }
        return image;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static Font bold(double size) {
        return new Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont((float) Math.max(4, size));
    }

    static Font plain(double size) {
        return new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont((float) Math.max(4, size));
    }

    static double textWidth(Graphics2D g, Font font, String text) {
        return g.getFontMetrics(font).stringWidth(text);
    }

    static double ascent(Graphics2D g, Font font) {
        FontMetrics metrics = g.getFontMetrics(font);
        return metrics.getAscent();
    }

    /** The font shrunk until {@code text} is no wider than {@code maxWidth}. */
    static Font fit(Graphics2D g, Font font, String text, double maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) {
            return font;
        }
        double width = textWidth(g, font, text);
        if (width <= maxWidth) {
            return font;
        }
        float size = (float) Math.max(4, font.getSize2D() * maxWidth / width);
        return font.deriveFont(size);
    }

    static void drawString(Graphics2D g, String text, double x, double y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, (float) x, (float) y);
    }

    /** A station name running up and to the right from {@code (x, y)}, as on strip maps. */
    private static void rotatedName(Graphics2D g, String text, double x, double y, Font font, Color color) {
        AffineTransform saved = g.getTransform();
        try {
            g.translate(x, y);
            g.rotate(Math.toRadians(-55));
            g.setFont(font);
            g.setColor(color);
            g.drawString(text, (float) (font.getSize2D() * 0.2), (float) (font.getSize2D() * 0.35));
        } finally {
            g.setTransform(saved);
        }
    }

    /** A solid arrowhead pointing right, {@code length} wide and {@code height} tall, centred on {@code (x, y)} vertically. */
    private static void arrow(Graphics2D g, double x, double y, double length, double height, Color color) {
        Path2D.Double head = new Path2D.Double();
        head.moveTo(x, y - height / 2);
        head.lineTo(x + length, y);
        head.lineTo(x, y + height / 2);
        head.closePath();
        g.setColor(color);
        g.fill(head);
    }

    static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }
}
