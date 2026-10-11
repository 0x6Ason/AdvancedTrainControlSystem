package dev.advancedtrains.atcs.network;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identifier rules shared by stations, platforms, lines, services, and boards.
 *
 * <p>Identifiers are short, lowercase, and safe to type in commands and on TrainCarts signs.
 * Display names are stored separately and may contain anything.
 */
public final class Ids {
    public static final int MAX_LENGTH = 32;
    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private Ids() {
    }

    public static boolean isValid(String id) {
        return id != null && id.length() <= MAX_LENGTH && VALID.matcher(id).matches();
    }

    /** Normalizes case and surrounding whitespace, then validates. */
    public static String normalize(String kind, String id) {
        if (id == null) {
            throw new IllegalArgumentException(kind + " id is missing");
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("Invalid " + kind + " id '" + id
                    + "': use up to " + MAX_LENGTH + " characters a-z, 0-9, '-' or '_'");
        }
        return normalized;
    }

    /** Turns a display name such as {@code "Golden Islands St."} into {@code "golden-islands-st"}. */
    public static String slugify(String text) {
        String ascii = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = NON_SLUG.matcher(ascii.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "unnamed" : slug;
    }
}
