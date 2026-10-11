package dev.advancedtrains.atcs.network;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

/** Parses line colours written as {@code #RRGGBB} or as a Minecraft colour name such as {@code gold}. */
public final class Colors {
    private Colors() {
    }

    public static int parse(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        NamedTextColor named = NamedTextColor.NAMES.value(value);
        if (named != null) {
            return named.value();
        }
        String hex = value.startsWith("#") ? value : "#" + value;
        TextColor color = TextColor.fromHexString(hex);
        if (color == null) {
            throw new IllegalArgumentException("Invalid colour '" + text
                    + "': use #RRGGBB or a Minecraft colour name such as red or gold");
        }
        return color.value();
    }

    public static String format(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
