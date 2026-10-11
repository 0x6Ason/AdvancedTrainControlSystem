package dev.advancedtrains.atcs.network;

import java.util.Objects;

/** Identifies a service as {@code line/service}, for example {@code a/local-north}. */
public record ServiceKey(String lineId, String serviceId) implements Comparable<ServiceKey> {

    public ServiceKey {
        lineId = Ids.normalize("line", lineId);
        serviceId = Ids.normalize("service", serviceId);
    }

    public static ServiceKey parse(String text) {
        Objects.requireNonNull(text, "text");
        int slash = text.indexOf('/');
        if (slash <= 0 || slash == text.length() - 1 || text.indexOf('/', slash + 1) >= 0) {
            throw new IllegalArgumentException("Expected line/service but found '" + text + "'");
        }
        return new ServiceKey(text.substring(0, slash), text.substring(slash + 1));
    }

    @Override
    public int compareTo(ServiceKey other) {
        int byLine = lineId.compareTo(other.lineId);
        return byLine != 0 ? byLine : serviceId.compareTo(other.serviceId);
    }

    @Override
    public String toString() {
        return lineId + "/" + serviceId;
    }
}
