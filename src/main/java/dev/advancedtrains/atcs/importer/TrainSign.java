package dev.advancedtrains.atcs.importer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A sign recognised as a TrainCarts action sign: a first line such as {@code [train]},
 * {@code [+train]}, {@code [!cart]} or {@code [train:left]}, and an action on the second line.
 *
 * @param action    lowercase first word of the second line, for example {@code station}
 * @param arguments remaining words of the second line
 */
public record TrainSign(ScannedSign source, String action, List<String> arguments) {
    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static Optional<TrainSign> parse(ScannedSign sign) {
        if (!isTrainHeader(sign.line(0))) {
            return Optional.empty();
        }
        String second = clean(sign.line(1));
        if (second.isEmpty()) {
            return Optional.empty();
        }
        String[] words = WHITESPACE.split(second);
        return Optional.of(new TrainSign(
                sign,
                words[0].toLowerCase(Locale.ROOT),
                List.of(words).subList(1, words.length)));
    }

    public static boolean isTrainHeader(String line) {
        String header = clean(line).toLowerCase(Locale.ROOT);
        if (header.length() < 3 || header.charAt(0) != '[' || header.charAt(header.length() - 1) != ']') {
            return false;
        }
        String body = header.substring(1, header.length() - 1).strip();
        if (body.startsWith("+") || body.startsWith("!")) {
            body = body.substring(1);
        }
        for (String mode : new String[] {"train", "cart"}) {
            if (body.startsWith(mode) && (body.length() == mode.length()
                    || body.charAt(mode.length()) == ':'
                    || Character.isWhitespace(body.charAt(mode.length())))) {
                return true;
            }
        }
        return false;
    }

    /** The third and fourth lines joined into one message, for example an announcement. */
    public String message() {
        return joinLines(source.line(2), source.line(3));
    }

    /** Removes colour codes and collapses whitespace. */
    static String clean(String text) {
        return WHITESPACE.matcher(FORMATTING.matcher(text).replaceAll("")).replaceAll(" ").strip();
    }

    /** Joins sign lines the way a reader would: one space between non-empty parts. */
    static String joinLines(String... lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String cleaned = clean(line);
            if (cleaned.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(cleaned);
        }
        return result.toString();
    }
}
