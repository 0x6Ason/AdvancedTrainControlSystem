package dev.advancedtrains.atcs.control;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fourth line of an {@code atcs station} sign: how a train that calls there is stopped and sent
 * on. Every part is optional and they may come in any order:
 *
 * <pre>
 * 10s continue        dwell 10 seconds, then carry on the way it came
 * 20 reverse          dwell 20 seconds, then go back (a terminus)
 * route               let TrainCarts path-finding pick the direction from the train's destination
 * 2.5m 30b            stop 2.5 blocks past the sign; launch over 30 blocks
 * gap 90s block       hold until 90 s after the last train of the line left, and until the
 *                     section ahead is clear
 * </pre>
 *
 * @param dwellSeconds   how long the train waits, or negative for the configured default
 * @param heading        where the train is launched afterwards
 * @param autoRoute      TrainCarts chooses the direction from the train's destination
 * @param offset         where the train is centred, in blocks past the sign (negative: before it)
 * @param launchDistance over how many blocks the train accelerates, or 0 for TrainCarts' choice
 * @param headwaySeconds minimum time after the previous departure of the same line, or negative
 *                       for the configured default
 * @param holdForSection whether to wait for the track ahead to be clear, or null for the default
 */
public record StopOptions(
        double dwellSeconds,
        Heading heading,
        boolean autoRoute,
        double offset,
        double launchDistance,
        double headwaySeconds,
        @Nullable Boolean holdForSection) {

    public static final StopOptions DEFAULT = new StopOptions(-1, Heading.CONTINUE, false, 0, 0, -1, null);

    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(s|sec|m|b)?");

    public static final String HELP = "use a dwell time (10s), a direction (continue, reverse, left, right), 'route',"
            + " an offset (2.5m), a launch distance (30b), 'gap <seconds>' or 'block'";

    /**
     * @throws IllegalArgumentException with a player-readable message when the text is invalid
     */
    public static StopOptions parse(String line) {
        String text = line.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return DEFAULT;
        }
        double dwell = -1;
        Heading heading = null;
        boolean route = false;
        double offset = 0;
        double launch = 0;
        double headway = -1;
        Boolean hold = null;
        String[] words = text.split("[\\s,]+");
        for (int index = 0; index < words.length; index++) {
            String word = words[index];
            if (word.isEmpty()) {
                continue;
            }
            switch (word) {
                case "route", "auto" -> {
                    route = true;
                    continue;
                }
                case "block", "section", "clear" -> {
                    hold = true;
                    continue;
                }
                case "noblock", "nosection" -> {
                    hold = false;
                    continue;
                }
                case "gap", "headway" -> {
                    if (index + 1 >= words.length) {
                        throw new IllegalArgumentException("'gap' needs the seconds, for example 'gap 60s'");
                    }
                    headway = seconds(words[++index], "gap");
                    continue;
                }
                default -> {
                }
            }
            if (word.startsWith("gap") && word.length() > 3) {
                headway = seconds(word.substring(3), "gap");
                continue;
            }
            Heading named = Heading.parse(word);
            if (named != null) {
                if (heading != null) {
                    throw new IllegalArgumentException("Only one direction, not '" + heading.word() + "' and '" + word + "'");
                }
                heading = named;
                continue;
            }
            Matcher matcher = NUMBER.matcher(word);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Unknown option '" + word + "': " + HELP);
            }
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2) == null ? "" : matcher.group(2);
            switch (unit) {
                case "m" -> offset = value;
                case "b" -> {
                    if (value <= 0) {
                        throw new IllegalArgumentException("The launch distance must be more than 0 blocks");
                    }
                    launch = value;
                }
                default -> {
                    if (value < 0) {
                        throw new IllegalArgumentException("The dwell time cannot be negative");
                    }
                    if (dwell >= 0) {
                        throw new IllegalArgumentException("Only one dwell time, not '" + format(dwell) + "s' and '" + word + "'");
                    }
                    dwell = value;
                }
            }
        }
        return new StopOptions(dwell, heading == null ? Heading.CONTINUE : heading, route, offset, launch, headway, hold);
    }

    private static double seconds(String word, String what) {
        Matcher matcher = NUMBER.matcher(word);
        if (!matcher.matches() || matcher.group(2) != null && !matcher.group(2).startsWith("s")) {
            throw new IllegalArgumentException("'" + what + "' needs a number of seconds, not '" + word + "'");
        }
        double value = Double.parseDouble(matcher.group(1));
        if (value < 0) {
            throw new IllegalArgumentException("'" + what + "' cannot be negative");
        }
        return value;
    }

    public boolean hasDwell() {
        return dwellSeconds >= 0;
    }

    public boolean hasHeadway() {
        return headwaySeconds >= 0;
    }

    /** A verb phrase for the sign builder ("This sign can ..."). */
    public String describe() {
        StringBuilder text = new StringBuilder("stop the trains that call here");
        if (hasDwell()) {
            text.append(" for ").append(format(dwellSeconds)).append(" s");
        }
        if (autoRoute) {
            text.append(", then let TrainCarts route them");
        } else {
            text.append(", then send them ").append(heading == Heading.CONTINUE ? "on" : heading.word());
        }
        if (offset != 0) {
            text.append(", centred ").append(format(Math.abs(offset))).append(" blocks ")
                    .append(offset > 0 ? "past" : "before").append(" the sign");
        }
        if (launchDistance > 0) {
            text.append(", launching over ").append(format(launchDistance)).append(" blocks");
        }
        if (hasHeadway()) {
            text.append("; headway ").append(format(headwaySeconds)).append(" s");
        }
        if (holdForSection != null) {
            text.append(holdForSection ? "; wait for a clear section ahead" : "; never wait for the section ahead");
        }
        return text.toString();
    }

    static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
