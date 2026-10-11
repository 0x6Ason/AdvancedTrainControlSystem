package dev.advancedtrains.atcs.operation;

import dev.advancedtrains.atcs.network.Network;
import dev.advancedtrains.atcs.network.Service;
import dev.advancedtrains.atcs.network.ServiceKey;
import dev.advancedtrains.atcs.network.ServiceMatch;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides which service a train runs.
 *
 * <p>An explicit assignment tag ({@code atcs:service=line/service}, written by ATCS signs and
 * commands) wins. Otherwise the first service whose {@link ServiceMatch} rules match is used.
 */
public final class ServiceResolver {
    public static final String TAG_PREFIX = "atcs:service=";
    /** Marks an assignment that was inferred from the train's route rather than set by an operator. */
    public static final String TAG_INFERRED = "atcs:inferred";
    /** Tag value of a train an operator cleared: no service, and no automatic assignment either. */
    public static final String MANUAL_VALUE = "none";

    private ServiceResolver() {
    }

    public static String assignmentTag(ServiceKey key) {
        return TAG_PREFIX + key;
    }

    public static boolean isAssignmentTag(String tag) {
        return tag.regionMatches(true, 0, TAG_PREFIX, 0, TAG_PREFIX.length());
    }

    public static String manualTag() {
        return TAG_PREFIX + MANUAL_VALUE;
    }

    public static boolean isInferredTag(String tag) {
        return tag.equalsIgnoreCase(TAG_INFERRED);
    }

    /** Whether the train's service was inferred from its route. */
    public static boolean isInferred(Collection<String> tags) {
        return tags.stream().anyMatch(ServiceResolver::isInferredTag);
    }

    /** Whether an operator cleared the train's service and switched automatic assignment off for it. */
    public static boolean isManual(Collection<String> tags) {
        return tags.stream().anyMatch(tag -> tag.equalsIgnoreCase(manualTag()));
    }

    /** The service named by an assignment tag, if any, whether or not it still exists. */
    public static Optional<ServiceKey> taggedService(Collection<String> tags) {
        for (String tag : tags) {
            if (isAssignmentTag(tag)) {
                try {
                    return Optional.of(ServiceKey.parse(tag.substring(TAG_PREFIX.length()).strip()));
                } catch (IllegalArgumentException ignored) {
                    // A malformed tag is treated as no assignment.
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<ServiceKey> resolve(TrainInfo train, Network network) {
        Optional<ServiceKey> tagged = taggedService(train.tags());
        if (tagged.isPresent() && network.service(tagged.get()).isPresent()) {
            return tagged;
        }
        if (isManual(train.tags())) {
            return Optional.empty();
        }
        for (Service service : network.services()) {
            if (!service.match().isEmpty() && matches(service.match(), train)) {
                return Optional.of(service.key());
            }
        }
        return Optional.empty();
    }

    static boolean matches(ServiceMatch match, TrainInfo train) {
        if (!match.destinations().isEmpty() && match.destinations().stream().noneMatch(destination ->
                destination.equalsIgnoreCase(train.destination())
                        || train.destinationRoute().stream().anyMatch(destination::equalsIgnoreCase))) {
            return false;
        }
        if (!match.tags().isEmpty() && match.tags().stream().noneMatch(wanted ->
                train.tags().stream().anyMatch(wanted::equalsIgnoreCase))) {
            return false;
        }
        return match.trainNames().isEmpty()
                || match.trainNames().stream().anyMatch(pattern -> glob(pattern).matcher(train.name()).matches());
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
