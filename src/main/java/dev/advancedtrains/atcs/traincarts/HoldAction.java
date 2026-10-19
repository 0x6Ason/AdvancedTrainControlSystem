package dev.advancedtrains.atcs.traincarts;

import com.bergerkiller.bukkit.tc.actions.GroupAction;
import com.bergerkiller.bukkit.tc.actions.WaitAction;
import com.bergerkiller.bukkit.tc.controller.status.TrainStatus;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Keeps a train standing at an {@code atcs station} sign while ATCS has a reason (the headway has
 * not passed, the section ahead is occupied). The reason is asked for twice a second; the train is
 * let go when there is none, or after the maximum hold time so a stale journey can never block a
 * platform for good.
 *
 * <p>The hold also ends the moment ATCS stops (the plugin is disabled or reloaded) and when any of
 * its checks fails: the action lives in TrainCarts' queue, beyond ATCS' own lifetime, and a hold
 * that nobody can release any more would freeze the train where it stands. A check that throws
 * every tick would do the same, since TrainCarts would run the hold again the next tick.
 */
final class HoldAction extends GroupAction implements WaitAction {
    private static final int CHECK_TICKS = 10;

    private final Supplier<Optional<String>> reason;
    private final int maxTicks;
    private final Consumer<String> timeout;
    private final BooleanSupplier mayTimeOut;
    private final BooleanSupplier alive;
    private final Consumer<Throwable> failure;
    private String current = "waiting";

    /**
     * @param reason     why the train must keep standing, or empty when it may go
     * @param maxTicks   the longest hold, after which {@code timeout} is told and the train goes
     * @param mayTimeOut false while the hold is a dispatcher's, which lasts until released
     * @param alive      false once ATCS has stopped, which ends the hold at once
     * @param failure    told when the reason check throws; the train goes
     */
    HoldAction(Supplier<Optional<String>> reason, int maxTicks, Consumer<String> timeout, BooleanSupplier mayTimeOut,
               BooleanSupplier alive, Consumer<Throwable> failure) {
        this.reason = reason;
        this.maxTicks = maxTicks;
        this.timeout = timeout;
        this.mayTimeOut = mayTimeOut;
        this.alive = alive;
        this.failure = failure;
    }

    @Override
    public boolean update() {
        try {
            return check();
        } catch (RuntimeException | LinkageError error) {
            try {
                failure.accept(error);
            } catch (RuntimeException | LinkageError ignored) {
                // The plugin that made this hold may be half unloaded; letting go is what matters.
            }
            return true;
        }
    }

    /** True when the train may go. */
    private boolean check() {
        if (!alive.getAsBoolean()) {
            return true;
        }
        int ticks = elapsedTicks();
        if (ticks % CHECK_TICKS == 0) {
            Optional<String> why = reason.get();
            if (why.isEmpty()) {
                return true;
            }
            current = why.get();
        }
        if (ticks >= maxTicks && mayTimeOut.getAsBoolean()) {
            timeout.accept(current);
            return true;
        }
        return false;
    }

    /** Why the train stands, as last checked. */
    String reason() {
        return current;
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
