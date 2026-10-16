package dev.advancedtrains.atcs.control;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
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
 * 0.4v                launch at 0.4 blocks per tick instead of TrainCarts' default
 * gap 90s block       hold until 90 s after the last train of the line left, and until the
 *                     section ahead is clear
 * even                hold a train that is catching up the one in front, so trains spread out
 * at :00 :30          leave at fixed minutes past the hour (at /15: every quarter hour)
 * </pre>
 *
 * @param dwellSeconds     how long the train waits, or negative for the configured default
 * @param heading          where the train is launched afterwards
 * @param autoRoute        TrainCarts chooses the direction from the train's destination
 * @param offset           where the train is centred, in blocks past the sign (negative: before it)
 * @param launchDistance   over how many blocks the train accelerates, or 0 for TrainCarts' choice
 * @param launchSpeed      the speed launched to, in blocks per tick, or 0 for TrainCarts' default
 * @param headwaySeconds   minimum time after the previous departure of the same line, or negative
 *                         for the configured default
 * @param holdForSection   whether to wait for the track ahead to be clear, or null for the default
 * @param evenSpacing      whether to hold a train that is catching up, or null for the default
 * @param departureMinutes minutes past the hour at which trains leave; empty for no timetable
 */
public record StopOptions(
        double dwellSeconds,
        Heading heading,
        boolean autoRoute,
        double offset,
        double launchDistance,
        double launchSpeed,
        double headwaySeconds,
        @Nullable Boolean holdForSection,
        @Nullable Boolean evenSpacing,
        List<Integer> departureMinutes) {

    public static final StopOptions DEFAULT = new StopOptions(-1, Heading.CONTINUE, false, 0, 0, 0, -1, null, null, List.of());

    private static final Pattern NUMBER = Pattern.compile("(-?\\d+(?:\\.\\d+)?)(s|sec|m|b|v)?");
    private static final Pattern MINUTE = Pattern.compile(":?(\\d{1,2})");
    private static final Pattern EVERY = Pattern.compile("/(\\d{1,2})");

    public static final String HELP = "use a dwell time (10s), a direction (continue, reverse, left, right), 'route',"
            + " an offset (2.5m), a launch distance (30b), a launch speed (0.4v), 'gap <seconds>', 'block', 'even'"
            + " or 'at :00 :30' (or 'at /15') for departures at fixed minutes";

    public StopOptions {
        departureMinutes = List.copyOf(new TreeSet<>(departureMinutes));
        for (int minute : departureMinutes) {
            if (minute < 0 || minute > 59) {
                throw new IllegalArgumentException("A departure minute must be between 0 and 59, not " + minute);
            }
        }
    }

    public StopOptions(double dwellSeconds, Heading heading, boolean autoRoute, double offset, double launchDistance,
                       double launchSpeed, double headwaySeconds, @Nullable Boolean holdForSection) {
        this(dwellSeconds, heading, autoRoute, offset, launchDistance, launchSpeed, headwaySeconds, holdForSection, null, List.of());
    }

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
        double speed = 0;
        double headway = -1;
        Boolean hold = null;
        Boolean even = null;
        List<Integer> minutes = new ArrayList<>();
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
                case "even", "regulate", "spread" -> {
                    even = true;
                    continue;
                }
                case "noeven", "noregulate" -> {
                    even = false;
                    continue;
                }
                case "gap", "headway" -> {
                    if (index + 1 >= words.length) {
                        throw new IllegalArgumentException("'gap' needs the seconds, for example 'gap 60s'");
                    }
                    headway = seconds(words[++index], "gap");
                    continue;
                }
                case "at", "depart", "timetable" -> {
                    int taken = 0;
                    while (index + 1 < words.length) {
                        Matcher every = EVERY.matcher(words[index + 1]);
                        Matcher minute = MINUTE.matcher(words[index + 1]);
                        if (every.matches()) {
                            int step = Integer.parseInt(every.group(1));
                            if (step < 1 || step > 60) {
                                throw new IllegalArgumentException("'at /" + step + "': the interval must be 1 to 60 minutes");
                            }
                            for (int m = 0; m < 60; m += step) {
                                minutes.add(m);
                            }
                        } else if (minute.matches() && words[index + 1].startsWith(":")) {
                            minutes.add(Integer.parseInt(minute.group(1)));
                        } else {
                            break;
                        }
                        index++;
                        taken++;
                    }
                    if (taken == 0) {
                        throw new IllegalArgumentException("'at' needs minutes past the hour, for example 'at :00 :30' or 'at /15'");
                    }
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
                case "v" -> {
                    if (value <= 0) {
                        throw new IllegalArgumentException("The launch speed must be more than 0 blocks per tick");
                    }
                    speed = value;
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
        try {
            return new StopOptions(dwell, heading == null ? Heading.CONTINUE : heading, route, offset, launch, speed, headway,
                    hold, even, minutes);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(exception.getMessage() + " (" + HELP + ")", exception);
        }
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

    public boolean hasTimetable() {
        return !departureMinutes.isEmpty();
    }

    /** The minutes as written on a sign or in network.yml: {@code :00 :30}. */
    public static String formatMinutes(List<Integer> minutes) {
        StringBuilder text = new StringBuilder();
        for (int minute : minutes) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(':').append(minute < 10 ? "0" : "").append(minute);
        }
        return text.toString();
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
        if (launchSpeed > 0) {
            text.append(", launching at ").append(format(launchSpeed)).append(" blocks per tick");
        }
        if (hasHeadway()) {
            text.append("; headway ").append(format(headwaySeconds)).append(" s");
        }
        if (holdForSection != null) {
            text.append(holdForSection ? "; wait for a clear section ahead" : "; never wait for the section ahead");
        }
        if (evenSpacing != null) {
            text.append(evenSpacing ? "; hold trains that catch up, to keep them spread out" : "; never hold for spacing");
        }
        if (hasTimetable()) {
            text.append("; depart at ").append(formatMinutes(departureMinutes)).append(" past the hour");
        }
        return text.toString();
    }

    static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
