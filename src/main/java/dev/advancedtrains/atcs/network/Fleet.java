package dev.advancedtrains.atcs.network;

import java.time.Duration;
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
 */
public record Fleet(int trains, Duration every, BlockPos spawn, Direction direction, String train) {
    public static final int MAX_TRAINS = 64;

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
                + " heading " + direction.name().toLowerCase(Locale.ROOT) + " as '" + train + "'";
    }
}
