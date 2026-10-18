package dev.advancedtrains.atcs.network;

import dev.advancedtrains.atcs.operation.Indicator;

import java.util.Objects;

/**
 * A redstone lamp or lever anywhere in the world that ATCS switches with the trains, bound by
 * command rather than by a sign: lit while a train is at a platform, approaching a station,
 * between two stations or inside a block, the rules an {@code atcs indicator} sign has.
 *
 * @param block  the lamp or lever
 * @param target what to watch, as on an indicator sign's line 3: {@code main}, {@code main:2} or {@code block <id>}
 * @param mode   the mode and filter, as on line 4: {@code approach a}, {@code between park}, {@code at} or empty
 * @param rule   the parsed rule
 */
public record Output(BlockPos block, String target, String mode, Indicator rule) {

    public Output {
        Objects.requireNonNull(block, "block");
        target = Objects.requireNonNull(target, "target").strip();
        mode = Objects.requireNonNull(mode, "mode").strip();
        Objects.requireNonNull(rule, "rule");
    }

    /** @throws IllegalArgumentException with a player-readable message when the rule text is invalid */
    public static Output of(BlockPos block, String target, String mode) {
        return new Output(block, target, mode, Indicator.parse(target, mode));
    }
}
