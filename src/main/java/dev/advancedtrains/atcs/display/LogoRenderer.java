package dev.advancedtrains.atcs.display;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * Draws the generated logos: a station roundel whose ring is split into the colours of the lines
 * calling there with the station's short code in the middle, and a line badge, a rounded square
 * in the line colour with the line's short name. One description, two renderers: PNG through
 * Java2D for maps and item frames, SVG text for the website. Deterministic, no files needed.
 */
public final class LogoRenderer {
    private static final Color INK = new Color(0x1F2328);
    private static final Color PAPER = Color.WHITE;
    private static final Color NO_LINE = new Color(0x9E9E9E);

    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private LogoRenderer() {
    }

    /** A station roundel of {@code size} pixels. */
    public static BufferedImage station(String code, List<Integer> lineColors, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double ring = Math.max(2, size / 6.0);
            double inset = ring / 2 + 1;
            double diameter = size - 2 * inset;
            g.setColor(PAPER);
            g.fill(new Ellipse2D.Double(1, 1, size - 2, size - 2));
            List<Integer> colors = lineColors.isEmpty() ? List.of(NO_LINE.getRGB() & 0xFFFFFF) : lineColors;
            g.setStroke(new BasicStroke((float) ring, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            double sweep = 360.0 / colors.size();
            for (int index = 0; index < colors.size(); index++) {
                g.setColor(new Color(colors.get(index) & 0xFFFFFF));
                // Arc2D angles run counter-clockwise from the right; start at the top and go clockwise.
                g.draw(new Arc2D.Double(inset, inset, diameter, diameter, 90 - index * sweep, -sweep, Arc2D.OPEN));
            }
            g.setColor(INK);
            g.setStroke(new BasicStroke(Math.max(1f, size / 40f)));
            g.draw(new Ellipse2D.Double(inset - ring / 2, inset - ring / 2, diameter + ring, diameter + ring));
            text(g, code.toUpperCase(Locale.ROOT), size / 2.0, size / 2.0, (size - 2 * ring) * 0.72, size * 0.42, INK);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** A line badge of {@code size} pixels. */
    public static BufferedImage lineBadge(String shortName, int color, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color fill = new Color(color & 0xFFFFFF);
            g.setColor(fill);
            double radius = size * 0.28;
            g.fill(new RoundRectangle2D.Double(1, 1, size - 2, size - 2, radius, radius));
            g.setColor(new Color(0, 0, 0, 70));
            g.setStroke(new BasicStroke(Math.max(1f, size / 40f)));
            g.draw(new RoundRectangle2D.Double(1, 1, size - 2, size - 2, radius, radius));
            text(g, shortName, size / 2.0, size / 2.0, size * 0.78, size * 0.6, onColor(color));
        } finally {
            g.dispose();
        }
        return image;
    }

    /** The station roundel as SVG. */
    public static String stationSvg(String code, List<Integer> lineColors, int size) {
        StringBuilder svg = new StringBuilder();
        double ring = Math.max(2, size / 6.0);
        double center = size / 2.0;
        double radius = center - ring / 2 - 1;
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(size).append(' ').append(size)
                .append("\" width=\"").append(size).append("\" height=\"").append(size).append("\" role=\"img\" aria-label=\"")
                .append(escape(code)).append("\">");
        svg.append("<circle cx=\"").append(center).append("\" cy=\"").append(center).append("\" r=\"").append(center - 1)
                .append("\" fill=\"#ffffff\"/>");
        List<Integer> colors = lineColors.isEmpty() ? List.of(NO_LINE.getRGB() & 0xFFFFFF) : lineColors;
        if (colors.size() == 1) {
            svg.append("<circle cx=\"").append(center).append("\" cy=\"").append(center).append("\" r=\"").append(radius)
                    .append("\" fill=\"none\" stroke=\"").append(hex(colors.get(0))).append("\" stroke-width=\"").append(ring).append("\"/>");
        } else {
            double sweep = 2 * Math.PI / colors.size();
            for (int index = 0; index < colors.size(); index++) {
                double start = -Math.PI / 2 + index * sweep;
                double end = start + sweep;
                svg.append("<path d=\"M ").append(round(center + radius * Math.cos(start))).append(' ').append(round(center + radius * Math.sin(start)))
                        .append(" A ").append(round(radius)).append(' ').append(round(radius)).append(" 0 0 1 ")
                        .append(round(center + radius * Math.cos(end))).append(' ').append(round(center + radius * Math.sin(end)))
                        .append("\" fill=\"none\" stroke=\"").append(hex(colors.get(index))).append("\" stroke-width=\"").append(ring).append("\"/>");
            }
        }
        svg.append("<circle cx=\"").append(center).append("\" cy=\"").append(center).append("\" r=\"").append(radius + ring / 2)
                .append("\" fill=\"none\" stroke=\"#1f2328\" stroke-width=\"").append(Math.max(1, size / 40.0)).append("\"/>");
        double fontSize = Math.min(size * 0.42, (size - 2 * ring) * 0.72 / Math.max(1, code.length()) * 1.6);
        svg.append("<text x=\"").append(center).append("\" y=\"").append(center).append("\" text-anchor=\"middle\" dominant-baseline=\"central\"")
                .append(" font-family=\"system-ui, Helvetica, Arial, sans-serif\" font-weight=\"700\" font-size=\"").append(round(fontSize))
                .append("\" fill=\"#1f2328\">").append(escape(code.toUpperCase(Locale.ROOT))).append("</text></svg>");
        return svg.toString();
    }

    /** The line badge as SVG. */
    public static String lineBadgeSvg(String shortName, int color, int size) {
        double radius = size * 0.28;
        double fontSize = Math.min(size * 0.6, size * 0.78 / Math.max(1, shortName.length()) * 1.6);
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size + "\" width=\"" + size + "\" height=\"" + size
                + "\" role=\"img\" aria-label=\"" + escape(shortName) + "\">"
                + "<rect x=\"1\" y=\"1\" width=\"" + (size - 2) + "\" height=\"" + (size - 2) + "\" rx=\"" + round(radius) + "\" fill=\"" + hex(color)
                + "\" stroke=\"rgba(0,0,0,0.27)\" stroke-width=\"" + Math.max(1, size / 40.0) + "\"/>"
                + "<text x=\"" + (size / 2.0) + "\" y=\"" + (size / 2.0) + "\" text-anchor=\"middle\" dominant-baseline=\"central\""
                + " font-family=\"system-ui, Helvetica, Arial, sans-serif\" font-weight=\"700\" font-size=\"" + round(fontSize)
                + "\" fill=\"" + hex(onColor(color).getRGB()) + "\">" + escape(shortName) + "</text></svg>";
    }

    /** Black or white, whichever reads better on the colour. */
    public static Color onColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        return luminance > 0.6 ? INK : PAPER;
    }

    /** Draws text centred at (x, y), shrinking the font until it fits {@code maxWidth}. Skips it where no font can be found. */
    private static void text(Graphics2D g, String text, double x, double y, double maxWidth, double maxHeight, Color color) {
        if (text.isEmpty()) {
            return;
        }
        try {
            float points = (float) maxHeight;
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(6, Math.round(points)));
            FontMetrics metrics = g.getFontMetrics(font);
            while (metrics.stringWidth(text) > maxWidth && points > 6) {
                points -= 1;
                font = font.deriveFont(points);
                metrics = g.getFontMetrics(font);
            }
            g.setFont(font);
            g.setColor(color);
            double baseline = y + (metrics.getAscent() - metrics.getDescent()) / 2.0;
            g.drawString(text, (float) (x - metrics.stringWidth(text) / 2.0), (float) baseline);
        } catch (Throwable fontless) {
            // A server without fonts installed draws the roundel without its code.
        }
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
