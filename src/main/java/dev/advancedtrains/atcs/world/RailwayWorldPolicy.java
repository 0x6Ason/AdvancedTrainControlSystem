package dev.advancedtrains.atcs.world;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Identifies the currently loaded worlds in which the railway integration is active.
 */
public interface RailwayWorldPolicy {
    boolean isActive(String worldName);

    Set<String> activeWorldNames();

    default String activeDescription() {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(activeWorldNames());
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    static RailwayWorldPolicy unrestricted() {
        return new RailwayWorldPolicy() {
            @Override
            public boolean isActive(String worldName) {
                return !normalize(worldName).isEmpty();
            }

            @Override
            public Set<String> activeWorldNames() {
                return Set.of();
            }

            @Override
            public String activeDescription() {
                return "all worlds (test policy)";
            }
        };
    }

    static RailwayWorldPolicy fixed(Set<String> activeWorlds) {
        Objects.requireNonNull(activeWorlds, "activeWorlds");
        Set<String> normalized = activeWorlds.stream()
                .map(RailwayWorldPolicy::normalize)
                .filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RailwayWorldPolicy() {
            @Override
            public boolean isActive(String worldName) {
                return normalized.contains(normalize(worldName));
            }

            @Override
            public Set<String> activeWorldNames() {
                return Set.copyOf(activeWorlds);
            }
        };
    }

    private static String normalize(String worldName) {
        return Objects.requireNonNull(worldName, "worldName").strip().toLowerCase(Locale.ROOT);
    }
}
