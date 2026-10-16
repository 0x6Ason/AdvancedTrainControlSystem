package dev.advancedtrains.atcs.network;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Objects;

/**
 * How many trains a service should have running and where ATCS spawns them, for lines that have
 * no TrainCarts spawn sign (a loop, for example, where trains are never destroyed).
 *
 * @param trains    trains to keep in service
 * @param every     the least time between two spawns, which also spaces the trains out
 * @param spawn     the rail block trains appear on
 * @param direction the way they set off
 * @param train     a TrainCarts spawn pattern such as {@code 4m} (four minecarts) or a saved train name
 * @param hours     when the service runs, by the server's clock; outside them no train is kept and
 *                  the ones running are stabled at the end of their trip; {@code null} for always
 * @param offPeak   fewer trains during part of the day, or {@code null}
 */
public record Fleet(int trains, Duration every, BlockPos spawn, Direction direction, String train,
                    @Nullable Hours hours, @Nullable OffPeak offPeak) {
    public static final int MAX_TRAINS = 64;

    /** A span of the day, {@code from} inclusive to {@code to} exclusive; it may pass midnight. */
    public record Hours(LocalTime from, LocalTime to) {
        public Hours {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.equals(to)) {
                throw new IllegalArgumentException("hours must span some time, not " + format(from) + "-" + format(to));
            }
        }

        /** Parses {@code 06:00-23:00}; the end may be earlier than the start for a span over midnight. */
        public static Hours parse(String text) {
            String[] parts = text.strip().split("\\s*-\\s*");
            if (parts.length != 2) {
                throw new IllegalArgumentException("'" + text + "' is not a span of hours; write it like 06:00-23:00");
            }
            try {
                return new Hours(LocalTime.parse(parts[0].length() == 4 ? "0" + parts[0] : parts[0]),
                        LocalTime.parse(parts[1].length() == 4 ? "0" + parts[1] : parts[1]));
            } catch (java.time.format.DateTimeParseException exception) {
                throw new IllegalArgumentException("'" + text + "' is not a span of hours; write it like 06:00-23:00");
            }
        }

        public boolean contains(LocalTime time) {
            if (from.isBefore(to)) {
                return !time.isBefore(from) && time.isBefore(to);
            }
            return !time.isBefore(from) || time.isBefore(to);
        }

        public String format() {
            return format(from) + "-" + format(to);
        }

        private static String format(LocalTime time) {
            return String.format(Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
        }
    }

    /** Fewer trains during these hours. */
    public record OffPeak(int trains, Hours hours) {
        public OffPeak {
            if (trains < 0 || trains > MAX_TRAINS) {
                throw new IllegalArgumentException("off-peak trains must be between 0 and " + MAX_TRAINS);
            }
            Objects.requireNonNull(hours, "hours");
        }
    }

    public enum Direction {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        private final int dx;
        private final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dz() {
            return dz;
        }

        /** The compass direction a Minecraft yaw points to (0 = south, 90 = west). */
        public static Direction fromYaw(float yaw) {
            double normalized = ((yaw % 360) + 360) % 360;
            if (normalized >= 315 || normalized < 45) {
                return SOUTH;
            }
            if (normalized < 135) {
                return WEST;
            }
            if (normalized < 225) {
                return NORTH;
            }
            return EAST;
        }

        public static Direction parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("direction must be north, east, south or west, not '" + text + "'");
            }
        }
    }

    public Fleet {
        if (trains < 1 || trains > MAX_TRAINS) {
            throw new IllegalArgumentException("fleet trains must be between 1 and " + MAX_TRAINS);
        }
        Objects.requireNonNull(every, "every");
        if (every.getSeconds() < 1 || every.getSeconds() > Duration.ofHours(24).getSeconds()) {
            throw new IllegalArgumentException("fleet every must be between 1 second and 24 hours");
        }
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(direction, "direction");
        train = Objects.requireNonNull(train, "train").strip();
        if (train.isEmpty()) {
            throw new IllegalArgumentException("fleet train pattern must not be empty");
        }
    }

    public Fleet(int trains, Duration every, BlockPos spawn, Direction direction, String train) {
        this(trains, every, spawn, direction, train, null, null);
    }

    /** How many trains the service should have running at this time of day. */
    public int target(LocalTime now) {
        if (hours != null && !hours.contains(now)) {
            return 0;
        }
        if (offPeak != null && offPeak.hours().contains(now)) {
            return offPeak.trains();
        }
        return trains;
    }

    public Fleet withHours(@Nullable Hours newHours) {
        return new Fleet(trains, every, spawn, direction, train, newHours, offPeak);
    }

    public Fleet withOffPeak(@Nullable OffPeak newOffPeak) {
        return new Fleet(trains, every, spawn, direction, train, hours, newOffPeak);
    }

    /** Parses {@code mm:ss}, {@code hh:mm:ss}, or a number of seconds. */
    public static Duration parseEvery(String text) {
        String value = text.strip();
        String[] parts = value.split(":");
        try {
            long seconds;
            if (parts.length == 1) {
                seconds = Long.parseLong(value.replaceAll("[sS]$", ""));
            } else if (parts.length == 2) {
                seconds = Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                seconds = Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            } else {
                throw new NumberFormatException();
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("'" + text + "' is not a time; write it like 00:35 (mm:ss)");
        }
    }

    /** Formats like the TrainCarts spawn sign: {@code mm:ss}, or {@code h:mm:ss} from an hour. */
    public static String formatEvery(Duration duration) {
        long total = duration.getSeconds();
        if (total >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    public String describe() {
        return trains + " train(s), one every " + formatEvery(every) + ", spawned at " + spawn.coordinates()
                + " heading " + direction.name().toLowerCase(Locale.ROOT) + " as '" + train + "'"
                + (hours == null ? "" : ", running " + hours.format())
                + (offPeak == null ? "" : ", " + offPeak.trains() + " train(s) " + offPeak.hours().format());
    }
}
