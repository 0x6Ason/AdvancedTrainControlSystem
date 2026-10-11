package dev.advancedtrains.atcs.network;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A platform (track face) of a station.
 *
 * @param id       identifier unique within the station, for example {@code 1} or {@code east}
 * @param name     optional display name; empty to derive one from the id
 * @param triggers positions of TrainCarts signs that fire when a train is at this platform
 */
public record Platform(String id, String name, List<BlockPos> triggers) {

    public Platform {
        id = Ids.normalize("platform", id);
        name = Objects.requireNonNullElse(name, "").strip();
        triggers = List.copyOf(triggers);
    }

    public static Platform of(String id) {
        return new Platform(id, "", List.of());
    }

    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }

    public Platform withName(String newName) {
        return new Platform(id, newName, triggers);
    }

    public Platform withTrigger(BlockPos trigger) {
        if (triggers.contains(trigger)) {
            return this;
        }
        List<BlockPos> updated = new java.util.ArrayList<>(triggers);
        updated.add(trigger);
        return new Platform(id, name, updated);
    }

    public Platform withoutTrigger(BlockPos trigger) {
        List<BlockPos> updated = new java.util.ArrayList<>(triggers);
        updated.remove(trigger);
        return new Platform(id, name, updated);
    }
}
