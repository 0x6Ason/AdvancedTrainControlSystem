package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps a train standing at an {@code atcs station} sign while ATCS has a reason (the headway has
 * not passed, the section ahead is occupied). The reason is asked for twice a second; the train is
 * let go when there is none, or after the maximum hold time so a stale journey can never block a
 * platform for good.
 */
final class HoldAction extends GroupAction implements WaitAction {
    private static final int CHECK_TICKS = 10;

    private final Supplier<Optional<String>> reason;
    private final int maxTicks;
    private final Consumer<String> timeout;
    private String current = "waiting";

    HoldAction(Supplier<Optional<String>> reason, int maxTicks, Consumer<String> timeout) {
        this.reason = reason;
        this.maxTicks = maxTicks;
        this.timeout = timeout;
    }

    @Override
    public boolean update() {
        int ticks = elapsedTicks();
        if (ticks % CHECK_TICKS == 0) {
            Optional<String> why = reason.get();
            if (why.isEmpty()) {
                return true;
            }
            current = why.get();
        }
        if (ticks >= maxTicks) {
            timeout.accept(current);
            return true;
        }
        return false;
    }

    @Override
    public boolean isMovementSuppressed() {
        return true;
    }

    @Override
    public List<TrainStatus> getStatusInfo() {
        String message = "ATCS holds the train: " + current;
        return List.of(() -> message);
    }
}
