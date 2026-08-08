package dev.advancedtrains.atcs.hud;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads temporary passenger-information overrides from TrainCarts tags until trips are authoritative.
 */
public record HudTagMetadata(
        Optional<String> line,
        Optional<String> serviceType,
        Optional<String> destination,
        Optional<String> nextStation) {

    public HudTagMetadata {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(serviceType, "serviceType");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nextStation, "nextStation");
    }

    public static HudTagMetadata parse(Collection<String> tags) {
        Objects.requireNonNull(tags, "tags");
        Optional<String> line = Optional.empty();
        Optional<String> service = Optional.empty();
        Optional<String> destination = Optional.empty();
        Optional<String> next = Optional.empty();
        for (String tag : tags) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (line.isEmpty()) {
                line = valueAfterPrefix(tag, lower, "atcs:line=", "line=", "line:");
            }
            if (service.isEmpty()) {
                service = valueAfterPrefix(tag, lower, "atcs:service=", "service=", "service:");
            }
            if (destination.isEmpty()) {
                destination = valueAfterPrefix(tag, lower, "atcs:destination=", "destination=");
            }
            if (next.isEmpty()) {
                next = valueAfterPrefix(tag, lower, "atcs:next=", "next=");
            }
        }
        return new HudTagMetadata(line, service, destination, next);
    }

    private static Optional<String> valueAfterPrefix(String original, String lower, String... prefixes) {
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix) && original.length() > prefix.length()) {
                String value = original.substring(prefix.length()).strip().replace('_', ' ');
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}

