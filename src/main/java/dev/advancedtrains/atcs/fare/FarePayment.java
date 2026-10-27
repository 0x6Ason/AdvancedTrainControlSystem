package dev.advancedtrains.atcs.fare;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

/** Money moves only through this boundary. An uncertain result must never be retried automatically. */
public interface FarePayment {
    enum Result { PAID, DECLINED, UNCERTAIN }

    /** No economy: automatic fares stay off. */
    FarePayment NONE = new FarePayment() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public boolean has(UUID player, double amount) {
            return false;
        }

        @Override
        public Result withdraw(UUID player, double amount) {
            return Result.DECLINED;
        }
    };

    boolean available();

    /** The economy in use, for status messages, or {@code null} when there is none. */
    default @Nullable String economyName() {
        return null;
    }

    boolean has(UUID player, double amount);

    Result withdraw(UUID player, double amount);
}
