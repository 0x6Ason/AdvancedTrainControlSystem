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
 * Draws the pictures displays show besides the board, the roadmap and the station sign: an
 * operator's own logo picture, the network map and a service status panel. Pure Java2D so it is
 * testable without a server; the {@link DisplayService} keeps the result as map colours.
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

    static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }
}
