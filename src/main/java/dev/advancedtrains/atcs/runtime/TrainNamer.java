package dev.advancedtrains.atcs.runtime;

import dev.advancedtrains.atcs.network.Line;
import dev.advancedtrains.atcs.network.Service;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Builds train names such as {@code A-to-main-2} from the configured format. */
public final class TrainNamer {
    public static final String DEFAULT_FORMAT = "<line>-<service>-<n>";
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final int MAX_NUMBER = 999;

    private TrainNamer() {
    }

    public static String name(String format, Line line, Service service, int number) {
        String name = format
                .replace("<line>", sanitize(line.shortName()))
                .replace("<service>", sanitize(service.key().serviceId()))
                .replace("<n>", Integer.toString(number));
        return sanitize(name);
    }

    /** The name with the lowest number that is not taken, or empty when none is free. */
    public static String firstFree(String format, Line line, Service service, Predicate<String> taken) {
        for (int number = 1; number <= MAX_NUMBER; number++) {
            String candidate = name(format, line, service, number);
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /** Keeps letters, digits, '-' and '_' so the name works in TrainCarts commands and signs. */
    static String sanitize(String text) {
        String clean = UNSAFE.matcher(text).replaceAll("-").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return clean.isEmpty() ? "train" : clean;
    }
}
