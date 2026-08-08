package dev.advancedtrains.atcs.discovery;

import org.bukkit.block.data.Rail;

import java.util.List;
import java.util.OptionalInt;

/**
 * Resolves shape-aware rail edge heights, including ascending rails.
 */
public final class RailGeometry {
    private RailGeometry() {
    }

    public static List<Edge> edges(Rail.Shape shape) {
        return switch (shape) {
            case NORTH_SOUTH -> List.of(new Edge(Direction.NORTH, 0), new Edge(Direction.SOUTH, 0));
            case EAST_WEST -> List.of(new Edge(Direction.EAST, 0), new Edge(Direction.WEST, 0));
            case ASCENDING_EAST -> List.of(new Edge(Direction.EAST, 1), new Edge(Direction.WEST, 0));
            case ASCENDING_WEST -> List.of(new Edge(Direction.EAST, 0), new Edge(Direction.WEST, 1));
            case ASCENDING_NORTH -> List.of(new Edge(Direction.NORTH, 1), new Edge(Direction.SOUTH, 0));
            case ASCENDING_SOUTH -> List.of(new Edge(Direction.NORTH, 0), new Edge(Direction.SOUTH, 1));
            case NORTH_EAST -> List.of(new Edge(Direction.NORTH, 0), new Edge(Direction.EAST, 0));
            case NORTH_WEST -> List.of(new Edge(Direction.NORTH, 0), new Edge(Direction.WEST, 0));
            case SOUTH_EAST -> List.of(new Edge(Direction.SOUTH, 0), new Edge(Direction.EAST, 0));
            case SOUTH_WEST -> List.of(new Edge(Direction.SOUTH, 0), new Edge(Direction.WEST, 0));
        };
    }

    public static boolean connects(
            Rail.Shape firstShape,
            int firstBaseY,
            Rail.Shape secondShape,
            int secondBaseY,
            Direction directionFromFirst) {
        OptionalInt firstHeight = edgeHeight(firstShape, directionFromFirst);
        OptionalInt secondHeight = edgeHeight(secondShape, directionFromFirst.opposite());
        return firstHeight.isPresent()
                && secondHeight.isPresent()
                && firstBaseY + firstHeight.orElseThrow()
                == secondBaseY + secondHeight.orElseThrow();
    }

    private static OptionalInt edgeHeight(Rail.Shape shape, Direction direction) {
        return edges(shape).stream()
                .filter(edge -> edge.direction() == direction)
                .mapToInt(Edge::height)
                .findFirst();
    }

    public record Edge(Direction direction, int height) {
        public Edge {
            if (height < 0 || height > 1) {
                throw new IllegalArgumentException("Rail edge height must be zero or one");
            }
        }
    }

    public enum Direction {
        NORTH(0, -1),
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0);

        private final int deltaX;
        private final int deltaZ;

        Direction(int deltaX, int deltaZ) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
        }

        public int deltaX() {
            return deltaX;
        }

        public int deltaZ() {
            return deltaZ;
        }

        public Direction opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case EAST -> WEST;
                case SOUTH -> NORTH;
                case WEST -> EAST;
            };
        }
    }
}
