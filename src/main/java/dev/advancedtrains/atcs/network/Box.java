package dev.advancedtrains.atcs.network;

import java.util.Objects;

/**
 * An axis-aligned box of whole blocks in one world, from a minimum to a maximum corner inclusive.
 * A wand selection and a station footprint are boxes. Written as
 * {@code world,x1,y1,z1,x2,y2,z2}.
 */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Box {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("A box needs a world");
        }
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Box corners are the wrong way round");
        }
    }

    /** The box spanned by two corners in any order. */
    public static Box of(BlockPos first, BlockPos second) {
        if (!first.world().equals(second.world())) {
            throw new IllegalArgumentException("Both corners must be in the same world");
        }
        return new Box(first.world(),
                Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
    }

    /** Parses {@code world,x1,y1,z1,x2,y2,z2}; a world name may itself contain commas. */
    public static Box parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] parts = text.strip().split(",");
        if (parts.length < 7) {
            throw new IllegalArgumentException("Expected world,x1,y1,z1,x2,y2,z2 but found '" + text + "'");
        }
        int[] numbers = new int[6];
        for (int index = 0; index < 6; index++) {
            String part = parts[parts.length - 6 + index].strip();
            try {
                numbers[index] = Integer.parseInt(part);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("'" + part + "' is not a whole number in '" + text + "'");
            }
        }
        String world = String.join(",", java.util.Arrays.copyOfRange(parts, 0, parts.length - 6)).strip();
        return of(new BlockPos(world, numbers[0], numbers[1], numbers[2]),
                new BlockPos(world, numbers[3], numbers[4], numbers[5]));
    }

    public String format() {
        return world + "," + minX + "," + minY + "," + minZ + "," + maxX + "," + maxY + "," + maxZ;
    }

    public BlockPos min() {
        return new BlockPos(world, minX, minY, minZ);
    }

    public BlockPos max() {
        return new BlockPos(world, maxX, maxY, maxZ);
    }

    public boolean contains(String otherWorld, double x, double y, double z) {
        return world.equals(otherWorld)
                && x >= minX && x < maxX + 1
                && y >= minY && y < maxY + 1
                && z >= minZ && z < maxZ + 1;
    }

    public boolean contains(BlockPos position) {
        return contains(position.world(), position.x(), position.y(), position.z());
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public int depth() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) width() * height() * depth();
    }

    /** The centre of the box, in block coordinates with a half-block offset. */
    public double centreX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centreY() {
        return (minY + maxY + 1) / 2.0;
    }

    public double centreZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    /** A short description for chat, for example {@code 12 x 4 x 1 blocks at -322,63,-802}. */
    public String describe() {
        return width() + " x " + height() + " x " + depth() + " blocks from " + minX + "," + minY + "," + minZ
                + " to " + maxX + "," + maxY + "," + maxZ + " in " + world;
    }

    @Override
    public String toString() {
        return format();
    }
}
