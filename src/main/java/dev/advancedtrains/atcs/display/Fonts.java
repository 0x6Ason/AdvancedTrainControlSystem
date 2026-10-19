package dev.advancedtrains.atcs.display;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The typefaces displays are drawn in, bundled so that every server draws the same signs: Inter
 * for signs, roadmaps and headings, close to the Helvetica of New York subway signage, and
 * Barlow Condensed for the split-flap departure board. Both are under the SIL Open Font License;
 * the licence texts are next to the fonts. If a font cannot be read, Java's sans serif is used.
 */
final class Fonts {
    static final Font BOLD = load("Inter-Bold.ttf", Font.BOLD);
    static final Font SEMIBOLD = load("Inter-SemiBold.ttf", Font.BOLD);
    /** Inter's display cut, drawn tighter for large type: station names on signs. */
    static final Font DISPLAY = load("InterDisplay-Bold.ttf", Font.BOLD);
    static final Font FLAP = load("BarlowCondensed-SemiBold.ttf", Font.BOLD);

    private Fonts() {
    }

    static Font of(Font font, double size) {
        return font.deriveFont((float) Math.max(4, size));
    }

    private static Font load(String file, int fallbackStyle) {
        try (InputStream in = Fonts.class.getResourceAsStream("/fonts/" + file)) {
            if (in != null) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            }
        } catch (IOException | FontFormatException ignored) {
            // Fall back below.
        }
        return new Font(Font.SANS_SERIF, fallbackStyle, 10);
    }
}
