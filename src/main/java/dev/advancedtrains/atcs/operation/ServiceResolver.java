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

    private ServiceResolver() {
    }

    public static String assignmentTag(ServiceKey key) {
        return TAG_PREFIX + key;
    }

    public static boolean isAssignmentTag(String tag) {
        return tag.regionMatches(true, 0, TAG_PREFIX, 0, TAG_PREFIX.length());
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
