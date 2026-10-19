package dev.advancedtrains.atcs.display;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Text and shape helpers shared by the sign, roadmap and split-flap renderers. */
final class Draw {
    static {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private Draw() {
    }

    static BufferedImage canvas(int width, int height, Color background) {
        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    static double width(Graphics2D g, Font font, String text) {
        return text.isEmpty() ? 0 : font.getStringBounds(text, g.getFontRenderContext()).getWidth();
    }

    /** The height of a capital letter, for centring text on a line or a bullet. */
    static double cap(Graphics2D g, Font font) {
        FontRenderContext context = g.getFontRenderContext();
        return font.createGlyphVector(context, "H").getVisualBounds().getHeight();
    }

    static void text(Graphics2D g, String text, double x, double baseline, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(text, (float) x, (float) baseline);
    }

    /** Text whose capitals are centred on {@code middle}. */
    static void textMiddle(Graphics2D g, String text, double x, double middle, Font font, Color color) {
        text(g, text, x, middle + cap(g, font) / 2, font, color);
    }

    static void textCentred(Graphics2D g, String text, double centre, double middle, Font font, Color color) {
        textMiddle(g, text, centre - width(g, font, text) / 2, middle, font, color);
    }

    /**
     * Text centred by its ink rather than its advance: a "1" carries more room on its left than
     * on its right, so centring the advance leaves it off to one side in a bullet or on a flap.
     * Horizontally the glyphs' outline is centred; vertically the capitals are, so a row of
     * flaps keeps one baseline.
     */
    static void inkCentred(Graphics2D g, String text, double centre, double middle, Font font, Color color) {
        if (text.isEmpty()) {
            return;
        }
        java.awt.font.GlyphVector glyphs = font.createGlyphVector(g.getFontRenderContext(), text);
        java.awt.geom.Rectangle2D ink = glyphs.getVisualBounds();
        double x = centre - (ink.getX() + ink.getWidth() / 2);
        double baseline = middle + cap(g, font) / 2;
        g.setColor(color);
        g.drawGlyphVector(glyphs, (float) x, (float) baseline);
    }

    /** The largest size up to {@code max} at which the text fits the width. */
    static double fit(Graphics2D g, Font font, String text, double maxWidth, double max) {
        double atMax = width(g, of(font, max), text);
        return atMax <= maxWidth || atMax == 0 ? max : Math.max(4, max * maxWidth / atMax);
    }

    static Font of(Font font, double size) {
        return Fonts.of(font, size);
    }

    /**
     * Breaks the text into at most {@code maxLines} lines no wider than {@code maxWidth}, at
     * spaces and after hyphens, with the lines as even as they can be ("Nerdy Estate / BLK. 1",
     * not "Nerdy Estate BLK. / 1"). Null when it does not fit.
     */
    static List<String> wrap(Graphics2D g, Font font, String text, double maxWidth, int maxLines) {
        List<String> tokens = new ArrayList<>();
        for (String word : text.strip().split("(?<=-)|\\s+")) {
            if (!word.isEmpty()) {
                tokens.add(word);
            }
        }
        if (tokens.isEmpty()) {
            return List.of("");
        }
        // The fewest lines it takes, filling each line as far as it goes.
        int lines = 1;
        int start = 0;
        for (int index = 1; index <= tokens.size(); index++) {
            if (width(g, font, join(tokens, start, index)) > maxWidth) {
                if (index - 1 == start) {
                    return null;
                }
                lines++;
                start = index - 1;
                if (width(g, font, join(tokens, start, index)) > maxWidth) {
                    return null;
                }
            }
        }
        if (lines > maxLines) {
            return null;
        }
        if (lines == 1) {
            return List.of(join(tokens, 0, tokens.size()));
        }
        // Then the same number of lines with the widest line as narrow as it can be.
        List<String> best = null;
        double bestWidth = Double.MAX_VALUE;
        for (int[] cuts : cuts(tokens.size(), lines - 1)) {
            List<String> candidate = new ArrayList<>();
            int from = 0;
            double widest = 0;
            for (int index = 0; index <= cuts.length; index++) {
                int to = index < cuts.length ? cuts[index] : tokens.size();
                String line = join(tokens, from, to);
                candidate.add(line);
                widest = Math.max(widest, width(g, font, line));
                from = to;
            }
            if (widest <= maxWidth && widest < bestWidth - 0.01) {
                best = candidate;
                bestWidth = widest;
            }
        }
        return best;
    }

    private static String join(List<String> tokens, int from, int to) {
        StringBuilder line = new StringBuilder();
        for (int index = from; index < to; index++) {
            if (!line.isEmpty() && !line.toString().endsWith("-")) {
                line.append(' ');
            }
            line.append(tokens.get(index));
        }
        return line.toString();
    }

    /** Every way of cutting {@code count} tokens into {@code cuts + 1} non-empty runs. */
    private static List<int[]> cuts(int count, int cuts) {
        List<int[]> result = new ArrayList<>();
        cut(new int[cuts], 0, 1, count, result);
        return result;
    }

    private static void cut(int[] cuts, int index, int from, int count, List<int[]> result) {
        if (index == cuts.length) {
            result.add(cuts.clone());
            return;
        }
        for (int at = from; at <= count - (cuts.length - index); at++) {
            cuts[index] = at;
            cut(cuts, index + 1, at + 1, count, result);
        }
    }

    /** The largest size up to {@code max} at which the text wraps into the lines and width given. */
    static double fitWrapped(Graphics2D g, Font font, String text, double maxWidth, int maxLines, double max, double min) {
        for (double size = max; size >= min; size -= Math.max(0.25, size * 0.04)) {
            if (wrap(g, of(font, size), text, maxWidth, maxLines) != null) {
                return size;
            }
        }
        return min;
    }

    /** Text colour that reads on the colour: white, or near-black on light colours (a yellow line). */
    static Color on(int rgb) {
        return LogoRenderer.onColor(rgb);
    }

    /**
     * A route bullet in the New York manner: a disc in the line colour with its badge text centred
     * on the capitals; a badge of three or more characters becomes a rounded pill.
     */
    static double bullet(Graphics2D g, String text, int rgb, double x, double centreY, double diameter) {
        // On whole pixels, so the disc and its letter come out of the map colours the same on every side.
        double d = Math.max(4, Math.round(diameter));
        double left = Math.round(x);
        double top = Math.round(centreY - d / 2);
        double width = d;
        Font font = of(Fonts.BOLD, d * (text.length() <= 1 ? 0.64 : 0.5));
        if (text.length() > 2) {
            width = Math.round(Math.max(d, width(g, font, text) + d * 0.5));
        }
        g.setColor(new Color(rgb));
        if (width > d) {
            g.fill(new RoundRectangle2D.Double(left, top, width, d, d, d));
        } else {
            g.fill(new Ellipse2D.Double(left, top, d, d));
        }
        double fitted = fit(g, font, text, width - d * 0.24, font.getSize2D());
        inkCentred(g, text, left + width / 2, top + d / 2, of(Fonts.BOLD, fitted), on(rgb));
        return width;
    }

    static double bulletWidth(Graphics2D g, String text, double diameter) {
        if (text.length() <= 2) {
            return diameter;
        }
        return Math.max(diameter, width(g, of(Fonts.BOLD, diameter * 0.5), text) + diameter * 0.5);
    }

    /** A solid arrow pointing right (or left) with its tip at {@code tipX}. */
    static void arrow(Graphics2D g, double tipX, double centreY, double length, double height, boolean left, Color color) {
        double sign = left ? -1 : 1;
        double shaft = height * 0.36;
        double head = Math.min(length * 0.55, height * 0.95);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(tipX, centreY);
        path.lineTo(tipX - sign * head, centreY - height / 2);
        path.lineTo(tipX - sign * head, centreY - shaft / 2);
        path.lineTo(tipX - sign * length, centreY - shaft / 2);
        path.lineTo(tipX - sign * length, centreY + shaft / 2);
        path.lineTo(tipX - sign * head, centreY + shaft / 2);
        path.lineTo(tipX - sign * head, centreY + height / 2);
        path.closePath();
        g.setColor(color);
        g.fill(path);
    }

    static Color darker(Color color, double factor) {
        return new Color((int) Math.round(color.getRed() * factor), (int) Math.round(color.getGreen() * factor),
                (int) Math.round(color.getBlue() * factor));
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
