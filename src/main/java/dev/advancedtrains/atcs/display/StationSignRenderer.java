package dev.advancedtrains.atcs.display;

import org.jspecify.annotations.Nullable;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A station's name sign for a platform wall or an entrance, in one of two manners: New York's
 * (white lettering on black under a white pinstripe, the route bullets of the lines that call)
 * or Hong Kong's (dark lettering on white over a band in the colours of the lines, each part
 * named). The frames decide the layout: a long sign sets the name on one line with the lines at
 * its end, a squarer one stacks the name and puts the lines beneath, and a single frame becomes
 * an emblem with the name and the lines in small.
 */
public final class StationSignRenderer {
    /** The manner of the sign. */
    public enum Style { MTA, MTR }

    /** A line that calls at the station: its badge, colour and name. */
    public record LineMark(String badge, int color, String name) {
    }

    static final Color BLACK = new Color(0x101010);
    static final Color SOFT = new Color(0xBDBDBD);
    static final Color MTR_PAPER = new Color(0xFBFBF8);
    static final Color MTR_INK = new Color(0x1A1A1A);
    static final Color MTR_SOFT = new Color(0x6B6B6B);

    private StationSignRenderer() {
    }

    /**
     * @param note a smaller line under the name, for example {@code Platform 2 · to Main St.}, or null
     */
    public static BufferedImage draw(String name, List<LineMark> lines, @Nullable String note, Style style, int width, int height) {
        BufferedImage image = Draw.canvas(width, height, style == Style.MTA ? BLACK : MTR_PAPER);
        Graphics2D g = Draw.graphics(image);
        try {
            if (style == Style.MTA) {
                mta(g, name, lines, note, width, height);
            } else {
                mtr(g, name, lines, note, width, height);
            }
            return image;
        } finally {
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- New York

    private static void mta(Graphics2D g, String name, List<LineMark> lines, @Nullable String note, int width, int height) {
        double u = Math.min(height, width * 0.9);
        double stripeY = u * 0.1;
        double stripe = Math.max(2, Math.round(u * 0.035));
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(0, stripeY, width, stripe));
        double pad = Draw.clamp(u * 0.13, 6, 40);
        double top = stripeY + stripe + u * 0.06;
        double bottom = height - u * 0.09;
        double aspect = (double) width / height;
        if (aspect >= 2.2) {
            // One line: the name, the bullets at the far end.
            double content = bottom - top;
            double d = content * (note == null ? 0.6 : 0.48);
            double gap = d * 0.18;
            double bulletsWidth = bulletsWidth(g, lines, d, gap);
            double textRoom = width - 2 * pad - (lines.isEmpty() ? 0 : bulletsWidth + pad);
            double nameCap = content * (note == null ? 0.5 : 0.4);
            double size = sizeForCap(g, Fonts.DISPLAY, nameCap);
            double fitted = Draw.fit(g, Fonts.DISPLAY, name, textRoom, size);
            double middle = note == null ? (top + bottom) / 2 : top + content * 0.36;
            List<String> nameLines = List.of(name);
            if (fitted < size * 0.62) {
                double two = Draw.fitWrapped(g, Fonts.DISPLAY, name, textRoom, 2, size * 0.62, 5);
                if (two > fitted) {
                    fitted = two;
                    nameLines = Draw.wrap(g, Draw.of(Fonts.DISPLAY, fitted), name, textRoom, 2);
                }
            }
            Font font = Draw.of(Fonts.DISPLAY, fitted);
            textBlock(g, nameLines, pad, middle, font, Color.WHITE, false, 0);
            if (note != null) {
                Font small = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, note, textRoom, sizeForCap(g, Fonts.SEMIBOLD, content * 0.16)));
                Draw.textMiddle(g, note, pad, top + content * 0.8, small, SOFT);
            }
            bulletRow(g, lines, width - pad - bulletsWidth, (top + bottom) / 2, d, gap);
            return;
        }
        // Squarer and taller signs: the name stacked, the bullets right under it, the two
        // centred together in the room under the stripe.
        double inner = width - 2 * pad;
        double room = bottom - top;
        int maxLines = aspect < 0.55 ? 4 : 3;
        double noteHeight = note == null ? 0 : u * 0.1;
        double size = Draw.fitWrapped(g, Fonts.DISPLAY, name, inner, maxLines, sizeForCap(g, Fonts.DISPLAY, room * 0.3), 6);
        double oneLine = Draw.fit(g, Fonts.DISPLAY, name, inner, size);
        if (oneLine >= size * 0.72) {
            maxLines = 1;
            size = oneLine;
        }
        Font font;
        List<String> nameLines;
        double cap;
        double d;
        double gap;
        int perRow;
        int bulletRows;
        double block;
        while (true) {
            font = Draw.of(Fonts.DISPLAY, size);
            cap = Draw.cap(g, font);
            List<String> wrapped = Draw.wrap(g, font, name, inner, maxLines);
            nameLines = wrapped == null ? List.of(name) : wrapped;
            d = Draw.clamp(cap * 1.35, 10, u * 0.3);
            gap = d * 0.2;
            perRow = Math.max(1, (int) Math.floor((inner + gap) / (d + gap)));
            bulletRows = lines.isEmpty() ? 0 : (int) Math.ceil((double) lines.size() / perRow);
            block = nameLines.size() * cap * 1.4 - cap * 0.4 + noteHeight
                    + (bulletRows == 0 ? 0 : cap * 0.9 + bulletRows * (d + gap) - gap);
            if (block <= room || size <= 6) {
                break;
            }
            size *= 0.94;
        }
        double y = top + (room - block) / 2 + cap;
        for (String line : nameLines) {
            Draw.text(g, line, pad, y, font, Color.WHITE);
            y += cap * 1.4;
        }
        y -= cap * 1.4;
        if (note != null) {
            Font small = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, note, inner, sizeForCap(g, Fonts.SEMIBOLD, u * 0.055)));
            y += noteHeight;
            Draw.text(g, note, pad, y - noteHeight * 0.2, small, SOFT);
        }
        double bulletsTop = y + cap * 0.9;
        for (int row = 0; row < bulletRows; row++) {
            List<LineMark> part = lines.subList(row * perRow, Math.min(lines.size(), (row + 1) * perRow));
            bulletRow(g, part, pad, bulletsTop + row * (d + gap) + d / 2, d, gap);
        }
    }

    private static double bulletsWidth(Graphics2D g, List<LineMark> lines, double d, double gap) {
        double total = 0;
        for (LineMark line : lines) {
            total += Draw.bulletWidth(g, line.badge(), d) + gap;
        }
        return Math.max(0, total - gap);
    }

    private static void bulletRow(Graphics2D g, List<LineMark> lines, double x, double middle, double d, double gap) {
        for (LineMark line : lines) {
            x += Draw.bullet(g, line.badge(), line.color(), x, middle, d) + gap;
        }
    }

    // ---------------------------------------------------------------- Hong Kong

    private static void mtr(Graphics2D g, String name, List<LineMark> lines, @Nullable String note, int width, int height) {
        double u = Math.min(height, width * 0.9);
        double bandHeight = lines.isEmpty() ? 0 : Math.round(Draw.clamp(u * 0.24, 12, 72));
        double pad = Draw.clamp(u * 0.12, 6, 40);
        double bandTop = height - bandHeight;
        band(g, lines, 0, bandTop, width, bandHeight);
        double aspect = (double) width / height;
        double top = pad * 0.9;
        double bottom = bandTop - pad * 0.7;
        double inner = width - 2 * pad;
        if (aspect >= 2.2) {
            double content = bottom - top;
            double size = sizeForCap(g, Fonts.DISPLAY, content * (note == null ? 0.5 : 0.4));
            double fitted = Draw.fit(g, Fonts.DISPLAY, name, inner, size);
            Font font = Draw.of(Fonts.DISPLAY, fitted);
            double middle = note == null ? (top + bottom) / 2 : top + content * 0.38;
            Draw.textMiddle(g, name, pad, middle, font, MTR_INK);
            if (note != null) {
                Font small = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, note, inner, sizeForCap(g, Fonts.SEMIBOLD, content * 0.17)));
                Draw.textMiddle(g, note, pad, top + content * 0.84, small, MTR_SOFT);
            }
            return;
        }
        double noteRoom = note == null ? 0 : u * 0.13;
        double room = bottom - top - noteRoom;
        int maxLines = aspect < 0.55 ? 4 : 3;
        double size = Draw.fitWrapped(g, Fonts.DISPLAY, name, inner, maxLines, sizeForCap(g, Fonts.DISPLAY, room * 0.42), 6);
        double oneLine = Draw.fit(g, Fonts.DISPLAY, name, inner, size);
        if (oneLine >= size * 0.72) {
            maxLines = 1;
            size = oneLine;
        }
        Font font = Draw.of(Fonts.DISPLAY, size);
        List<String> nameLines = Draw.wrap(g, font, name, inner, maxLines);
        if (nameLines == null) {
            nameLines = List.of(name);
        }
        double cap = Draw.cap(g, font);
        while (nameLines.size() * cap * 1.38 > room && size > 6) {
            size *= 0.94;
            font = Draw.of(Fonts.DISPLAY, size);
            cap = Draw.cap(g, font);
            List<String> again = Draw.wrap(g, font, name, inner, maxLines);
            if (again != null) {
                nameLines = again;
            }
        }
        double block = nameLines.size() * cap * 1.38 - cap * 0.38 + noteRoom;
        double y = top + (room + noteRoom - block) / 2 + cap;
        for (String line : nameLines) {
            Draw.text(g, line, width / 2.0 - Draw.width(g, font, line) / 2, y, font, MTR_INK);
            y += cap * 1.38;
        }
        if (note != null) {
            Font small = Draw.of(Fonts.SEMIBOLD, Draw.fit(g, Fonts.SEMIBOLD, note, inner, sizeForCap(g, Fonts.SEMIBOLD, u * 0.06)));
            Draw.text(g, note, width / 2.0 - Draw.width(g, small, note) / 2, y - cap * 0.38 + Draw.cap(g, small) * 1.2, small, MTR_SOFT);
        }
    }

    /**
     * The band of line colours, one part per line; each part carries the line's name where it
     * fits, else its badge.
     */
    private static void band(Graphics2D g, List<LineMark> lines, double x, double y, double width, double height) {
        if (lines.isEmpty()) {
            return;
        }
        double part = width / lines.size();
        List<Double> edges = new ArrayList<>();
        for (int index = 0; index <= lines.size(); index++) {
            edges.add(x + Math.round(index * part));
        }
        // Every part says the same kind of thing: all the lines' names, or all their badges.
        Font font = Draw.of(Fonts.BOLD, sizeForCap(g, Fonts.BOLD, height * 0.36));
        boolean names = true;
        for (int index = 0; index < lines.size(); index++) {
            names &= Draw.width(g, font, lines.get(index).name()) <= edges.get(index + 1) - edges.get(index) - height * 0.6;
        }
        for (int index = 0; index < lines.size(); index++) {
            LineMark line = lines.get(index);
            double left = edges.get(index);
            double right = edges.get(index + 1);
            g.setColor(new Color(line.color()));
            g.fill(new Rectangle2D.Double(left, y, right - left, height));
            double room = right - left - height * 0.6;
            String text = names ? line.name() : Draw.width(g, font, line.badge()) <= room ? line.badge() : "";
            if (!text.isEmpty()) {
                Draw.inkCentred(g, text, (left + right) / 2, y + height / 2, font, Draw.on(line.color()));
            }
            if (index > 0) {
                g.setColor(MTR_PAPER);
                g.fill(new Rectangle2D.Double(left - 0.5, y, 1, height));
            }
        }
    }

    // ---------------------------------------------------------------- text

    /** The font size whose capitals are {@code cap} pixels high. */
    private static double sizeForCap(Graphics2D g, Font font, double cap) {
        double at100 = Draw.cap(g, Draw.of(font, 100));
        return Math.max(4, cap * 100 / at100);
    }

    private static void textBlock(Graphics2D g, List<String> lines, double x, double middle, Font font, Color color,
                                  boolean centred, double width) {
        double cap = Draw.cap(g, font);
        double lineHeight = cap * 1.38;
        double first = middle + cap / 2 - (lines.size() - 1) * lineHeight / 2;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            double left = centred ? x + (width - Draw.width(g, font, line)) / 2 : x;
            Draw.text(g, line, left, first + index * lineHeight, font, color);
        }
    }
}
