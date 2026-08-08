package dev.advancedtrains.atcs.legacy;

import java.util.regex.Pattern;

final class LegacySignText {
    private static final Pattern MINECRAFT_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    private LegacySignText() {
    }

    static String clean(String text) {
        return MINECRAFT_FORMATTING.matcher(text == null ? "" : text).replaceAll("");
    }
}

