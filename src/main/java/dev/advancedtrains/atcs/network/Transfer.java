package dev.advancedtrains.atcs.network;

import org.jspecify.annotations.Nullable;

/**
 * A walking connection between two stations, for example two stations a short walk apart that
 * passengers change between. The website draws it as a connector (dotted when passengers leave
 * the station, solid for a passage inside it), the trip planner can walk it, and arrival
 * announcements mention the lines at the other end.
 *
 * @param a       one station (the smaller id)
 * @param b       the other station
 * @param walk    walking time in seconds, or null to estimate it from the station positions
 * @param outside whether passengers leave the station, through the gates, to make the change
 */
public record Transfer(String a, String b, @Nullable Integer walk, boolean outside) {
    public static final int MAX_WALK_SECONDS = 3600;
    /** Minecraft walking speed in blocks per second. */
    public static final double WALK_SPEED = 4.3;
    /** Real paths are longer than the straight line between the stations. */
    public static final double ROUTE_FACTOR = 1.3;
    /** Time spent at the gates when leaving one station and entering the other. */
    public static final int GATE_SECONDS = 10;

    public Transfer {
        String first = Ids.normalize("station", a);
        String second = Ids.normalize("station", b);
        if (first.equals(second)) {
            throw new IllegalArgumentException("a transfer needs two different stations");
        }
        if (first.compareTo(second) > 0) {
            String swap = first;
            first = second;
            second = swap;
        }
        a = first;
        b = second;
        if (walk != null && (walk < 1 || walk > MAX_WALK_SECONDS)) {
            throw new IllegalArgumentException("walk must be between 1 second and 1 hour");
        }
    }

    public static Transfer of(String a, String b) {
        return new Transfer(a, b, null, true);
    }

    public boolean involves(String stationId) {
        return a.equals(stationId) || b.equals(stationId);
    }

    /** The station at the other end from {@code stationId}. */
    public String other(String stationId) {
        return a.equals(stationId) ? b : a;
    }

    /** The key two transfers between the same stations share, whatever order they were given in. */
    public String key() {
        return key(a, b);
    }

    public static String key(String first, String second) {
        return first.compareTo(second) <= 0 ? first + "|" + second : second + "|" + first;
    }

    public Transfer withWalk(@Nullable Integer seconds) {
        return new Transfer(a, b, seconds, outside);
    }

    public Transfer withOutside(boolean leaves) {
        return new Transfer(a, b, walk, leaves);
    }

    /**
     * Walking time for a straight-line distance: walking pace over a somewhat longer real path,
     * plus time at the gates when passengers leave the station. Rounded up to 5 seconds.
     */
    public static int estimateSeconds(double distance, boolean outside) {
        double seconds = distance * ROUTE_FACTOR / WALK_SPEED + (outside ? GATE_SECONDS : 0);
        return (int) Math.min(MAX_WALK_SECONDS, Math.max(5, Math.ceil(seconds / 5) * 5));
    }
}
