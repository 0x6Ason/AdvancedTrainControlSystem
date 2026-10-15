package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.importer.TrainSign;
import dev.advancedtrains.atcs.network.PlatformRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text of a TrainCarts {@code station} sign into the text of an {@code atcs station}
 * sign for the platform it is bound to, keeping what the sign did: its delay, direction,
 * {@code route}, centre offset, launch distance and launch speed.
 *
 * <pre>
 * [+train]          [+train]
 * station 30 2m  ->  atcs station
 * 00:05             main:2
 * reverse           5s reverse 2m 30b
 * </pre>
 *
 * @param lines the four new lines
 * @param notes what could not be carried over, in words for the operator
 */
public record StationSignConversion(List<String> lines, List<String> notes) {

    private static final Pattern FORMATTING = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)([a-z]*)");
    private static final Pattern CLOCK = Pattern.compile("(\\d+):(\\d+)(?::(\\d+))?");

    public StationSignConversion {
        lines = List.copyOf(lines);
        notes = List.copyOf(notes);
    }

    /** Whether the lines are an {@code atcs} sign already. */
    public static boolean isAtcsSign(List<String> lines) {
        return lines.size() >= 2 && TrainSign.isTrainHeader(lines.get(0))
                && clean(lines.get(1)).toLowerCase(Locale.ROOT).startsWith("atcs");
    }

    /**
     * @param lines the sign's four lines
     * @return the conversion, or empty when the sign is not a TrainCarts station sign
     */
    public static Optional<StationSignConversion> convert(List<String> lines, PlatformRef platform) {
        if (lines.size() < 4 || !TrainSign.isTrainHeader(lines.get(0))) {
            return Optional.empty();
        }
        String[] action = clean(lines.get(1)).toLowerCase(Locale.ROOT).split("\\s+");
        if (!action[0].equals("station")) {
            return Optional.empty();
        }
        List<String> notes = new ArrayList<>();
        List<String> options = new ArrayList<>();

        double launchDistance = 0;
        double offset = 0;
        for (int index = 1; index < action.length; index++) {
            Matcher matcher = NUMBER.matcher(action[index]);
            if (!matcher.matches()) {
                notes.add("ignored '" + action[index] + "' on line 2");
            } else if (matcher.group(2).equals("m")) {
                offset = Double.parseDouble(matcher.group(1));
            } else if (matcher.group(2).isEmpty()) {
                launchDistance = Double.parseDouble(matcher.group(1));
            } else {
                notes.add("ignored '" + action[index] + "' on line 2");
            }
        }

        String delayText = clean(lines.get(2)).toLowerCase(Locale.ROOT);
        double delay = -1;
        if (!delayText.isEmpty()) {
            delay = parseSeconds(delayText);
            if (delay < 0) {
                notes.add("could not read the delay '" + delayText + "'; the default dwell applies");
            }
        }

        Heading heading = null;
        boolean route = false;
        double speed = 0;
        for (String word : clean(lines.get(3)).toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (word.equals("route")) {
                route = true;
                continue;
            }
            Heading named = Heading.parse(word);
            if (named != null) {
                heading = named;
                continue;
            }
            Matcher matcher = NUMBER.matcher(word);
            if (matcher.matches() && matcher.group(2).isEmpty()) {
                speed = Double.parseDouble(matcher.group(1));
            } else {
                notes.add("ignored '" + word + "' on line 4");
            }
        }

        if (delay >= 0) {
            options.add(format(delay) + "s");
        } else if (delayText.isEmpty() && heading == null && !route) {
            notes.add("held trains until redstone released them; now they dwell the default time and continue");
        } else if (delayText.isEmpty()) {
            options.add("0s");
        }
        if (heading != null && heading != Heading.CONTINUE) {
            options.add(heading.word());
        }
        if (route) {
            options.add("route");
        }
        if (offset != 0) {
            options.add(format(offset) + "m");
        }
        if (launchDistance > 0) {
            options.add(format(launchDistance) + "b");
        }
        if (speed > 0) {
            options.add(format(speed) + "v");
        }
        String header = clean(lines.get(0));
        if (!header.startsWith("[+") && !header.startsWith("[!")) {
            notes.add("a [train] header: the sign acts only while powered, as before; sides no longer choose the direction");
        }
        return Optional.of(new StationSignConversion(
                List.of(header, "atcs station", platform.toString(), String.join(" ", options)), notes));
    }

    /** Seconds from {@code 5}, {@code 5s}, {@code 2m}, {@code 00:05} or {@code 1:02:03}; -1 if unreadable. */
    static double parseSeconds(String text) {
        Matcher clock = CLOCK.matcher(text);
        if (clock.matches()) {
            int first = Integer.parseInt(clock.group(1));
            int second = Integer.parseInt(clock.group(2));
            if (clock.group(3) == null) {
                return first * 60 + second;
            }
            return first * 3600 + second * 60 + Integer.parseInt(clock.group(3));
        }
        Matcher number = NUMBER.matcher(text);
        if (!number.matches()) {
            return -1;
        }
        double value = Double.parseDouble(number.group(1));
        return switch (number.group(2)) {
            case "", "s", "sec", "secs" -> value;
            case "m", "min", "mins" -> value * 60;
            case "h", "hr", "hrs" -> value * 3600;
            case "ms" -> value / 1000;
            default -> -1;
        };
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String clean(String text) {
        return FORMATTING.matcher(text).replaceAll("").strip();
    }
}
