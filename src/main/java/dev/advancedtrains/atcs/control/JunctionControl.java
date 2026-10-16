package dev.advancedtrains.atcs.control;

import dev.advancedtrains.atcs.operation.RailGraph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Priority at a merge: a train about to leave a platform whose next stretch runs into a junction
 * another train is approaching waits for it, unless it goes first. The late train goes first;
 * otherwise the line listed earlier in {@code control.priority}; otherwise the nearer train. A
 * train already too close to the junction to be stopped always goes first. Pure logic over what
 * the rail graph and the estimator know.
 */
public final class JunctionControl {
    /** Trains further than this from a junction, in blocks along the track, are not in the way. */
    public static final double RANGE_BLOCKS = 160;
    /** A train nearer than this cannot be held back any more. */
    public static final double COMMITTED_BLOCKS = 24;

    /** Another train on its way to a junction, {@code remaining} blocks before it. */
    public record Approach(String trainId, String lineId, boolean late, double remaining) {
    }

    private JunctionControl() {
    }

    /**
     * Why the standing train must wait, or empty when it may go.
     *
     * @param lineId    the standing train's line
     * @param late      whether it is late
     * @param ahead     the junctions on its next stretch, nearest first, with their distance from the platform
     * @param coming    other trains approaching each junction, by junction id
     * @param priority  line ids in order of precedence; lines not listed come last
     */
    public static Optional<String> giveWay(String trainId, String lineId, boolean late, List<RailGraph.Passing> ahead,
                                           Map<String, List<Approach>> coming, List<String> priority) {
        for (RailGraph.Passing junction : ahead) {
            if (junction.distance() > RANGE_BLOCKS) {
                break;
            }
            for (Approach other : coming.getOrDefault(junction.nodeId(), List.of())) {
                if (other.trainId().equals(trainId) || other.remaining() <= 0 || other.remaining() > RANGE_BLOCKS) {
                    continue;
                }
                if (!goesFirst(lineId, late, junction.distance(), other, priority)) {
                    return Optional.of("junction: giving way to " + other.trainId() + " (" + Math.round(other.remaining()) + " blocks away)");
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the standing train goes before the approaching one. */
    static boolean goesFirst(String lineId, boolean late, double distance, Approach other, List<String> priority) {
        if (other.remaining() < COMMITTED_BLOCKS) {
            return false;
        }
        if (late != other.late()) {
            return late;
        }
        int mine = rank(lineId, priority);
        int theirs = rank(other.lineId(), priority);
        if (mine != theirs) {
            return mine < theirs;
        }
        return distance < other.remaining();
    }

    private static int rank(String lineId, List<String> priority) {
        int index = priority.indexOf(lineId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
