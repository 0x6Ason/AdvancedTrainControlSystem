package dev.advancedtrains.atcs.network;

import java.util.Objects;

/**
 * A block position in a named world. Written as {@code world,x,y,z} in configuration files.
 */
public record BlockPos(String world, int x, int y, int z) {

    public BlockPos {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("World name must not be blank");
        }
    }

    /**
     * Parses {@code world,x,y,z}. The coordinates are taken from the end so that a world name
     * containing a comma still round-trips.
     */
    public static BlockPos parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.strip().split(",");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Expected world,x,y,z but found '" + text + "'");
        }
        int n = parts.length;
        String world = String.join(",", java.util.Arrays.copyOf(parts, n - 3)).strip();
        try {
            return new BlockPos(
                    world,
                    Integer.parseInt(parts[n - 3].strip()),
                    Integer.parseInt(parts[n - 2].strip()),
                    Integer.parseInt(parts[n - 1].strip()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid coordinates in '" + text + "'", exception);
        }
    }

    public String format() {
        return world + "," + x + "," + y + "," + z;
    }

    /** Short form for chat output, without the world name. */
    public String coordinates() {
        return x + " " + y + " " + z;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public double distanceSquared(double otherX, double otherY, double otherZ) {
        double dx = x + 0.5 - otherX;
        double dy = y + 0.5 - otherY;
        double dz = z + 0.5 - otherZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distanceSquared(BlockPos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistanceSquared(BlockPos other) {
        double dx = x - other.x;
        double dz = z - other.z;
        return dx * dx + dz * dz;
    }

    @Override
    public String toString() {
        return format();
    }
}
