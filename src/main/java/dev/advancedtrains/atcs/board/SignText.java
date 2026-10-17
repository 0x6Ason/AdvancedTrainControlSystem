package dev.advancedtrains.atcs.board;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * How wide text is on a sign, in the pixels of Minecraft's default font, so a board can tell
 * whether a line will be cut off and say something shorter instead. The widths are those of the
 * ASCII glyphs of the default font (with the one-pixel gap after each), bold adds a pixel per
 * character, and anything outside ASCII is taken to be as wide as the widest glyphs.
 */
public final class SignText {
    /** A sign line is cut off beyond this many pixels. */
    public static final int LINE_WIDTH = 90;

    private SignText() {
    }

    /** The width of the text on one sign line, bold counted. */
    public static int width(Component component) {
        return width(component, component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
    }

    public static boolean fits(Component component) {
        return width(component) <= LINE_WIDTH;
    }

    private static int width(Component component, boolean bold) {
        boolean thisBold = switch (component.decoration(TextDecoration.BOLD)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> bold;
        };
        int total = 0;
        if (component instanceof TextComponent text) {
            total += width(text.content(), thisBold);
        }
        for (Component child : component.children()) {
            total += width(child, thisBold);
        }
        return total;
    }

    /** The width of plain text, in default-font pixels. */
    public static int width(String text, boolean bold) {
        int total = 0;
        for (int index = 0; index < text.length(); index++) {
            total += glyph(text.charAt(index)) + (bold ? 1 : 0);
        }
        return total;
    }

    /** Glyph width plus the gap after it; the default font's ASCII widths. */
    static int glyph(char c) {
        if (c >= 0x80) {
            return 9;
        }
        return switch (c) {
            case ' ' -> 4;
            case 'i', '!', ',', '.', ':', ';', '\'', '|', '`' -> 2;
            case 'l' -> 3;
            case 't', 'I', '[', ']' -> 4;
            case 'f', 'k', '"', '(', ')', '*', '{', '}', '<', '>' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }
}
