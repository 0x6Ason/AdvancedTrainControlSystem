package dev.advancedtrains.atcs.legacy;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public record LegacySignHeader(
        String raw,
        Mode mode,
        boolean alwaysPowered,
        boolean inverted,
        String options) {

    public LegacySignHeader {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(options, "options");
    }

    public static Optional<LegacySignHeader> parse(String rawHeader) {
        Objects.requireNonNull(rawHeader, "rawHeader");
        String clean = LegacySignText.clean(rawHeader).strip();
        if (clean.length() < 3 || clean.charAt(0) != '[' || clean.charAt(clean.length() - 1) != ']') {
            return Optional.empty();
        }

        String body = clean.substring(1, clean.length() - 1).strip();
        boolean alwaysPowered = false;
        boolean inverted = false;
        if (body.startsWith("+")) {
            alwaysPowered = true;
            body = body.substring(1);
        } else if (body.startsWith("!")) {
            inverted = true;
            body = body.substring(1);
        }

        String lower = body.toLowerCase(Locale.ROOT);
        Mode mode;
        int modeLength;
        if (hasModePrefix(lower, "train")) {
            mode = Mode.TRAIN;
            modeLength = "train".length();
        } else if (hasModePrefix(lower, "cart")) {
            mode = Mode.CART;
            modeLength = "cart".length();
        } else {
            return Optional.empty();
        }

        return Optional.of(new LegacySignHeader(
                clean,
                mode,
                alwaysPowered,
                inverted,
                body.substring(modeLength).strip()));
    }

    private static boolean hasModePrefix(String body, String mode) {
        if (!body.startsWith(mode)) {
            return false;
        }
        return body.length() == mode.length()
                || body.charAt(mode.length()) == ':'
                || Character.isWhitespace(body.charAt(mode.length()));
    }

    public enum Mode {
        TRAIN,
        CART
    }
}

